package com.aell.layerlist.dev;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.easyplace.Controllable;
import com.aell.layerlist.easyplace.LookSpoof;
import com.aell.layerlist.easyplace.PlaceSolver;
import com.aell.layerlist.easyplace.Potting;
import com.aell.layerlist.easyplace.StateFix;
import com.aell.layerlist.easyplace.Stripping;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Дамп B: достижимость состояний решателем. Ничего не ставит — гоняет
 * {@link PlaceSolver} в живом мире по станциям площадки и, если решатель ничего не нашёл,
 * добивает полным перебором {@link CovSim}: так «мод не умеет» отделяется от «таким кликом
 * это состояние не получить вовсе».
 */
@Environment(EnvType.CLIENT)
public final class DumpSolver implements CovJob
{
    private static final String HEADER = String.join(",",
            "key", "id", "state", "category", "station", "solver", "pose", "turn", "sneak",
            "miss_scored", "miss_other", "fix", "probes", "solve_ms", "expected", "breaks_no_turn",
            "keep_free", "sweep");

    private static final int SWEEP_BUDGET = 24000;

    private CovOut out;
    private List<Block> blocks = new ArrayList<>();
    private int blockIndex;
    private List<BlockState> pending = List.of();
    private int stateIndex;
    private boolean fillingWas;
    private boolean noTurnWas;

    @Override
    public String name()
    {
        return "B/solver.csv";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            this.out = CovOut.open("solver.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("не открыть solver.csv: " + e);
            return false;
        }

        // матрица должна показывать полную способность модуля, а не текущие галочки
        this.fillingWas = LayerListConfigs.EP_FIX_FILLING.getBooleanValue();
        this.noTurnWas = LayerListConfigs.EP_NO_TURN.getBooleanValue();

        LayerListConfigs.EP_FIX_FILLING.setBooleanValue(true);
        LayerListConfigs.EP_NO_TURN.setBooleanValue(false);

        this.blocks = new ArrayList<>(BuiltInRegistries.BLOCK.stream().toList());

        return mc.level != null && mc.player != null;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        if (level == null || player == null)
        {
            return true;
        }

        if (this.stateIndex >= this.pending.size())
        {
            if (this.blockIndex >= this.blocks.size())
            {
                return true;
            }

            Block block = this.blocks.get(this.blockIndex++);

            this.pending = candidates(block);
            this.stateIndex = 0;

            if ((this.blockIndex & 63) == 0)
            {
                CovOut.log("B: " + this.blockIndex + "/" + this.blocks.size() + ", строк "
                        + this.out.doneCount());
            }

            return false;
        }

        BlockState wanted = this.pending.get(this.stateIndex++);
        String key = key(wanted);

        if (this.out.has(key))
        {
            return false;
        }

        try
        {
            this.out.row(this.evaluate(level, player, wanted, key));
        }
        catch (RuntimeException | LinkageError e)
        {
            this.out.row(List.of(key, id(wanted), props(wanted), "ERROR", "", "", "", "", "", "", "",
                    "", "", "", "", "", "", String.valueOf(e)));
        }

        return false;
    }

    private List<String> evaluate(ClientLevel level, LocalPlayer player, BlockState wanted, String key)
    {
        String id = id(wanted);
        String props = props(wanted);

        if (DumpRegistry.derivedState(wanted))
        {
            return row(key, id, props, "DERIVED", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
        }

        net.minecraft.world.item.Item item = wanted.getBlock().asItem();

        if (item == Items.AIR)
        {
            if (Stripping.plainFor(wanted) != null)
            {
                return row(key, id, props, "NEEDS_TOOL_CREATE", "", "", "", "", "", "", "", "axe",
                        "", "", "", "", "", "");
            }

            if (Potting.plant(wanted) != null)
            {
                return row(key, id, props, "NEEDS_TOOL_CREATE", "", "", "", "", "", "", "",
                        "pot:" + BuiltInRegistries.ITEM.getKey(Potting.plant(wanted)), "", "", "", "", "", "");
            }

            return row(key, id, props, "UNREACHABLE", "", "", "", "", "", "", "", "no_item", "", "",
                    "", "", "", "");
        }

        ItemStack stack = new ItemStack(item);

        if (! (item instanceof BlockItem))
        {
            return row(key, id, props, "UNKNOWN", "", "", "", "", "", "", "", "not_a_block_item", "",
                    "", "", "", "", "");
        }

        LookSpoof.Pose now = LookSpoof.Pose.of(player);
        long start = System.nanoTime();

        CovArena.Station station = null;
        BlockState expected = null;
        LookSpoof.Pose pose = null;
        int probes = 0;
        int bestMiss = Integer.MAX_VALUE;
        String solver = "";
        boolean sweepExact = false;
        boolean sweepFull = true;
        boolean swept = false;

        // Станции перебираем все, пока не найдётся та, где состояние выходит точно:
        // фонарь встаёт и на полу, но подвешенным он бывает только под потолком, и
        // остановка на первой же станции, где «хоть что-то встало», соврала бы.
        for (CovArena.Station candidate : order(stack))
        {
            BlockPos target = CovArena.target(candidate);
            PlaceSolver.Plan plan = PlaceSolver.solve(level, player, InteractionHand.MAIN_HAND, stack,
                    target, wanted, null);

            BlockState got = plan == null ? null : plan.expected();
            LookSpoof.Pose gotPose = plan == null ? null : plan.pose();
            String tag = plan == null ? null : (plan.perfect() ? "PERFECT" : "PARTIAL");

            // решателю нечего искать (нет управляемых свойств) или он не нашёл ничего —
            // добиваем полным перебором, чтобы отличить «мод не умеет» от «нельзя вовсе»
            if (plan == null || ! plan.perfect())
            {
                CovSim.Result sim = CovSim.sweep(level, player, InteractionHand.MAIN_HAND, stack,
                        target, wanted, SWEEP_BUDGET);

                probes += sim.probes();
                swept = true;
                sweepExact |= sim.exact();
                sweepFull &= sim.probes() <= SWEEP_BUDGET;

                if (sim.expected() != null
                        && (got == null || sim.matched() > CovSim.matched(got, wanted)))
                {
                    got = sim.expected();
                    gotPose = sim.pose();
                    tag = plan == null ? "NULL" : "SOLVER_MISS";
                }
            }

            if (got == null)
            {
                continue;
            }

            int miss = CovSim.diff(got, wanted).size();

            if (miss < bestMiss)
            {
                bestMiss = miss;
                station = candidate;
                expected = got;
                pose = gotPose;
                solver = tag == null ? "" : tag;
            }

            if (miss == 0)
            {
                break;
            }
        }

        double ms = (System.nanoTime() - start) / 1_000_000.0D;
        String sweep = ! swept ? "" : sweepExact ? "EXACT" : sweepFull ? "NONE" : "BUDGET";

        if (expected == null)
        {
            return row(key, id, props, "UNREACHABLE", "", "no_placement", "", "", "", "", "",
                    "no_support_or_context", Integer.toString(probes), fmt(ms), "", "", "", sweep);
        }

        List<Property<?>> diff = CovSim.diff(expected, wanted);
        Set<Property<?>> scored = new LinkedHashSet<>(Controllable.scored(wanted));
        List<String> missScored = new ArrayList<>();
        List<String> missOther = new ArrayList<>();

        for (Property<?> property : diff)
        {
            (scored.contains(property) ? missScored : missOther).add(property.getName());
        }

        StateFix.Fix fix = StateFix.plan(wanted, expected);
        String fixId = fix == null ? "" : fix.id() + ":" + fix.path();
        boolean turn = pose != null && (pose.yaw() != now.yaw() || pose.pitch() != now.pitch());
        String breaks = "";

        if (turn)
        {
            breaks = Boolean.toString(this.breaksWithoutTurn(level, player, stack, station, wanted));
        }

        // клетке мало опоры — ей нужна ещё и пустота вокруг: грядка, тропинка, кактус.
        // Такую клетку схемы надо класть последней, иначе ваниль снесёт её в тот же тик.
        String keepFree = Boolean.toString(! survives(level, expected,
                CovArena.target(CovArena.Station.BOX)));

        String category;

        if (diff.isEmpty())
        {
            category = turn ? "NEEDS_POSE" : "DIRECT";
        }
        else if (! missScored.isEmpty())
        {
            // перебор нашёл точный клик, а решатель нет — это пробел мода;
            // перебор дошёл до конца и не нашёл — состояние кликом не получить вовсе
            category = sweepExact ? "SOLVER_MISS" : "NONE".equals(sweep) ? "UNREACHABLE" : "MISMATCH";
        }
        else if (fix != null)
        {
            category = "NEEDS_FOLLOWUP";
        }
        else
        {
            category = "NEEDS_WORLD";
        }

        return row(key, id, props, category, station == null ? "" : station.name(), solver,
                pose == null ? "" : fmt(pose.yaw()) + "/" + fmt(pose.pitch()),
                Boolean.toString(turn), pose != null && pose.sneak() ? "true" : "false",
                String.join("|", missScored), String.join("|", missOther), fixId,
                Integer.toString(probes), fmt(ms), props(expected), breaks, keepFree, sweep);
    }

    private boolean breaksWithoutTurn(ClientLevel level, LocalPlayer player, ItemStack stack,
            @Nullable CovArena.Station station, BlockState wanted)
    {
        if (station == null)
        {
            return true;
        }

        LayerListConfigs.EP_NO_TURN.setBooleanValue(true);

        try
        {
            PlaceSolver.Plan plan = PlaceSolver.solve(level, player, InteractionHand.MAIN_HAND, stack,
                    CovArena.target(station), wanted, null);

            return plan == null || ! plan.perfect();
        }
        finally
        {
            LayerListConfigs.EP_NO_TURN.setBooleanValue(false);
        }
    }

    private static boolean survives(ClientLevel level, BlockState state, BlockPos pos)
    {
        try
        {
            return state.canSurvive(level, pos);
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static List<CovArena.Station> order(ItemStack stack)
    {
        if (com.aell.layerlist.easyplace.WaterPlace.is(stack))
        {
            return List.of(CovArena.Station.ONWATER, CovArena.Station.WATER, CovArena.Station.FLOOR,
                    CovArena.Station.WALL, CovArena.Station.CEIL, CovArena.Station.BOX);
        }

        // от самой скупой опоры к самой щедрой: станция в дампе — это и есть ответ на
        // вопрос «что должно стоять раньше этой клетки»
        return List.of(CovArena.Station.FLOOR, CovArena.Station.WALL, CovArena.Station.CEIL,
                CovArena.Station.SOIL, CovArena.Station.FARM, CovArena.Station.SAND,
                CovArena.Station.ONWATER, CovArena.Station.WATER, CovArena.Station.DARK,
                CovArena.Station.BOX);
    }

    /** Состояния к проверке: значение по умолчанию плюс каждое значение каждого свойства. */
    static List<BlockState> candidates(Block block)
    {
        BlockState def = block.defaultBlockState();
        Set<BlockState> out = new LinkedHashSet<>();

        out.add(def);

        for (Property<?> property : block.getStateDefinition().getProperties())
        {
            for (BlockState state : vary(def, property))
            {
                out.add(state);
            }
        }

        return new ArrayList<>(out);
    }

    private static <T extends Comparable<T>> List<BlockState> vary(BlockState def, Property<T> property)
    {
        List<BlockState> out = new ArrayList<>();

        for (T value : property.getPossibleValues())
        {
            out.add(def.setValue(property, value));
        }

        return out;
    }

    static String id(BlockState state)
    {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());

        return id == null ? state.getBlock().toString() : id.toString();
    }

    static String props(BlockState state)
    {
        StringBuilder sb = new StringBuilder();

        for (Property<?> property : state.getBlock().getStateDefinition().getProperties())
        {
            if (sb.length() > 0)
            {
                sb.append(';');
            }

            sb.append(property.getName()).append('=').append(value(state, property));
        }

        return sb.toString();
    }

    private static <T extends Comparable<T>> String value(BlockState state, Property<T> property)
    {
        return property.getName(state.getValue(property));
    }

    static String key(BlockState state)
    {
        String props = props(state);

        return props.isEmpty() ? id(state) : id(state) + "[" + props + "]";
    }

    private static String fmt(double value)
    {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static List<String> row(String... fields)
    {
        return List.of(fields);
    }

    @Override
    public void close()
    {
        LayerListConfigs.EP_FIX_FILLING.setBooleanValue(this.fillingWas);
        LayerListConfigs.EP_NO_TURN.setBooleanValue(this.noTurnWas);

        if (this.out != null)
        {
            CovOut.log("solver.csv: строк " + this.out.doneCount());
            this.out.close();
        }
    }
}
