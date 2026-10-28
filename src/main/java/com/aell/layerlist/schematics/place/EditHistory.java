package com.aell.layerlist.schematics.place;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

/**
 * История шагов редактора размещения: снимки (origin, поворот, отражение) с указателем
 * текущего. Новый шаг после отмены отрезает «будущее», как в любом редакторе. Хранится не
 * больше {@link #LIMIT} снимков — самые старые уходят; исходное состояние для «Отмены» редактор
 * держит отдельно и от этого предела не зависит.
 */
@Environment(EnvType.CLIENT)
public final class EditHistory
{
    public static final int LIMIT = 100;

    /** Подряд идущие мелкие шаги одного рода (колесо) в пределах этого окна — один шаг истории. */
    private static final long MERGE_MS = 600L;

    private final List<State> states = new ArrayList<>();
    private int index;
    private @Nullable String mergeKind;
    private long mergeAt;

    public EditHistory(State initial)
    {
        this.states.add(initial);
        this.index = 0;
    }

    public State current()
    {
        return this.states.get(this.index);
    }

    public void record(State state)
    {
        this.record(state, null);
    }

    /**
     * Запомнить новое состояние. С {@code mergeKind} шаг сливается с предыдущим того же рода, если
     * тот был только что: десять щелчков колесом — одна отмена, а не десять.
     */
    public void record(State state, @Nullable String mergeKind)
    {
        if (state.equals(this.current()))
        {
            return;
        }

        long now = Util.getMillis();
        boolean merge = mergeKind != null && mergeKind.equals(this.mergeKind) && now - this.mergeAt <= MERGE_MS
                && this.index > 0 && this.index == this.states.size() - 1;

        this.mergeKind = mergeKind;
        this.mergeAt = now;

        while (this.states.size() > this.index + 1)
        {
            this.states.removeLast();
        }

        if (merge)
        {
            this.states.set(this.index, state);

            return;
        }

        this.states.add(state);

        if (this.states.size() > LIMIT)
        {
            this.states.removeFirst();
        }

        this.index = this.states.size() - 1;
    }

    public boolean canUndo()
    {
        return this.index > 0;
    }

    public boolean canRedo()
    {
        return this.index < this.states.size() - 1;
    }

    public @Nullable State undo()
    {
        if (! this.canUndo())
        {
            return null;
        }

        this.mergeKind = null;

        return this.states.get(--this.index);
    }

    public @Nullable State redo()
    {
        if (! this.canRedo())
        {
            return null;
        }

        this.mergeKind = null;

        return this.states.get(++this.index);
    }

    /** Снимок положения размещения. */
    public record State(BlockPos origin, Rotation rotation, Mirror mirror)
    {
        public static State of(SchematicPlacement p)
        {
            return new State(p.getOrigin().immutable(), p.getRotation(), p.getMirror());
        }
    }
}
