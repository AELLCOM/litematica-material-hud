package com.aell.layerlist.schematics.preview;

import com.aell.layerlist.schematics.library.VoxelSnapshot;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Готовность постройки по блокам — для 3D-превью «блок в блок» (запрос 09.10.2026). Каждая клетка снимка
 * схемы сверяется с миром на месте размещения: тот же блок — {@link #BUILT}, пусто — {@link #MISSING},
 * другой блок — {@link #WRONG}; чанк не загружен — {@link #UNKNOWN}. Ожидаемый блок берётся из мира схемы
 * Litematica (там он уже повёрнут и отражён, как у размещения).
 *
 * <p>Сборщик модели красит грани: построенные — как есть, непостроенные — тёмным голубоватым «призраком»,
 * чужие — красным оттенком. Считается кусками на клиентском потоке ({@link Scan}), только у снимка 1:1.
 */
@Environment(EnvType.CLIENT)
public final class BuildStatus
{
    public static final byte UNKNOWN = 0;
    public static final byte BUILT = 1;
    public static final byte MISSING = 2;
    public static final byte WRONG = 3;

    private final byte[] cells;
    private final int sizeX;
    private final int sizeZ;
    final long signature;
    private final int built;
    private final int known;

    private BuildStatus(byte[] cells, int sizeX, int sizeZ, long signature, int built, int known)
    {
        this.cells = cells;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.signature = signature;
        this.built = built;
        this.known = known;
    }

    /** Статус клетки снимка; вне снимка — {@link #UNKNOWN}. */
    public byte at(int x, int y, int z)
    {
        int index = (y * this.sizeZ + z) * this.sizeX + x;
        return x < 0 || z < 0 || x >= this.sizeX || z >= this.sizeZ || index < 0 || index >= this.cells.length
                ? UNKNOWN : this.cells[index];
    }

    /** Построено, % от известных блоков; −1 — ни одного не видно. */
    public int builtPercent()
    {
        return this.known == 0 ? -1 : (int) (this.built * 100L / this.known);
    }

    /** Цвет вершины с учётом статуса: построенный и неизвестный — как есть. */
    public static int tint(byte status, int argb)
    {
        if (status == MISSING)
        {
            // «Призрак»: голограмма — серое по яркости блока, сдвинутое в светлый холодный голубой, чтобы
            // читалось и на тёмном фоне превью и не путалось с тенью
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int l = (r * 77 + g * 150 + b * 29) >> 8;
            int gray = l * 70 / 100;
            return (argb & 0xFF000000) | mix(gray, 120, 0.5F) << 16 | mix(gray, 160, 0.5F) << 8 | mix(gray, 220, 0.5F);
        }

        if (status == WRONG)
        {
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            return (argb & 0xFF000000) | mix(r, 235, 0.55F) << 16 | mix(g, 70, 0.55F) << 8 | mix(b, 60, 0.55F);
        }

        return argb;
    }

    private static int mix(int from, int to, float k)
    {
        return Math.max(0, Math.min(255, Math.round(from + (to - from) * k)));
    }

    /**
     * Сверка по кускам: {@link #step} за кадр проходит не больше заданного числа клеток, {@link #result} —
     * когда всё пройдено. Только клиентский поток (мир игрока и мир схемы).
     */
    public static final class Scan
    {
        private final VoxelSnapshot snapshot;
        private final @Nullable SchematicPlacement placement;
        private final BlockPos origin;
        private final BlockPos min;
        private final byte[] cells;
        private final BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        private final BlockPos.MutableBlockPos world = new BlockPos.MutableBlockPos();
        private int index;
        private long signature = 1125899906842597L;
        private int built;
        private int known;

        public Scan(VoxelSnapshot snapshot, SchematicPlacement placement)
        {
            this.snapshot = snapshot;
            this.placement = placement;
            this.origin = placement.getOrigin();
            LitematicaSchematic schematic = placement.getSchematic();
            this.min = schematic != null ? enclosingMin(schematic) : BlockPos.ZERO;
            this.cells = new byte[(int) snapshot.volume()];
        }

        /**
         * Снимок, снятый с мира схем по коробке мира ({@code WorldSnapshotJob}): клетка {@code (x, y, z)} — это блок
         * {@code worldMin + (x, y, z)}, поворачивать нечего. Только снимок 1:1.
         */
        public Scan(VoxelSnapshot snapshot, BlockPos worldMin)
        {
            this.snapshot = snapshot;
            this.placement = null;
            this.origin = worldMin;
            this.min = BlockPos.ZERO;
            this.cells = new byte[(int) snapshot.volume()];
        }

        /** Пройти до {@code budget} клеток; истина — готово. */
        public boolean step(int budget)
        {
            Minecraft mc = Minecraft.getInstance();
            WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();

            if (mc.level == null || schematicWorld == null)
            {
                this.index = this.cells.length;
                return true;
            }

            int sx = this.snapshot.sizeX();
            int sz = this.snapshot.sizeZ();
            int end = Math.min(this.cells.length, this.index + budget);

            for (; this.index < end; ++this.index)
            {
                int i = this.index;
                int x = i % sx;
                int z = (i / sx) % sz;
                int y = i / (sx * sz);

                if (this.snapshot.idAt(x, y, z) == 0)
                {
                    continue;
                }

                if (this.placement == null)
                {
                    this.world.set(x + this.origin.getX(), y + this.origin.getY(), z + this.origin.getZ());
                }
                else
                {
                    this.local.set(x + this.min.getX(), y + this.min.getY(), z + this.min.getZ());
                    BlockPos turned = PositionUtils.getTransformedBlockPos(this.local, this.placement.getMirror(),
                            this.placement.getRotation());
                    this.world.set(turned.getX() + this.origin.getX(), turned.getY() + this.origin.getY(),
                            turned.getZ() + this.origin.getZ());
                }
                byte status = UNKNOWN;

                if (mc.level.hasChunk(this.world.getX() >> 4, this.world.getZ() >> 4))
                {
                    BlockState wanted = schematicWorld.getBlockState(this.world);
                    BlockState have = mc.level.getBlockState(this.world);

                    if (! wanted.isAir())
                    {
                        status = have.is(wanted.getBlock()) ? BUILT
                                : have.isAir() || have.canBeReplaced() ? MISSING : WRONG;
                    }
                }

                this.cells[i] = status;

                if (status != UNKNOWN)
                {
                    ++this.known;
                    this.built += status == BUILT ? 1 : 0;
                    this.signature = this.signature * 31L + i * 4L + status;
                }
            }

            return this.index >= this.cells.length;
        }

        public BuildStatus result()
        {
            return new BuildStatus(this.cells, this.snapshot.sizeX(), this.snapshot.sizeZ(), this.signature,
                    this.built, this.known);
        }
    }

    /** Минимальный угол всех регионов схемы — от него считает снимок библиотеки. */
    static BlockPos enclosingMin(LitematicaSchematic schematic)
    {
        Map<String, BlockPos> positions = schematic.getAreaPositions();
        Map<String, BlockPos> sizes = schematic.getAreaSizes();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;

        for (Map.Entry<String, BlockPos> area : positions.entrySet())
        {
            BlockPos size = sizes.get(area.getKey());

            if (size == null)
            {
                continue;
            }

            BlockPos pos = area.getValue();
            minX = Math.min(minX, pos.getX() + (size.getX() < 0 ? size.getX() + 1 : 0));
            minY = Math.min(minY, pos.getY() + (size.getY() < 0 ? size.getY() + 1 : 0));
            minZ = Math.min(minZ, pos.getZ() + (size.getZ() < 0 ? size.getZ() + 1 : 0));
        }

        return minX == Integer.MAX_VALUE ? BlockPos.ZERO : new BlockPos(minX, minY, minZ);
    }

    /** Статус для сборки (может отсутствовать). */
    static byte statusAt(@Nullable BuildStatus status, int x, int y, int z)
    {
        return status == null ? UNKNOWN : status.at(x, y, z);
    }
}
