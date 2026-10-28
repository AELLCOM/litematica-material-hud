package com.aell.layerlist.schematics.place;

import com.aell.layerlist.shaded.ui.Ui;
import com.aell.layerlist.ui.Theme;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Рамка размещения в мире, пока открыт редактор: контур габарита (жёлтый во время
 * перетаскивания, синий в покое), мин. угол, ручки подъёма на восьми углах (под курсором и схваченная —
 * золотая и крупнее), «тень» габарита на земле и сетка чанков под ним при «По сетке чанков». Рисуется ванильными гизмо в {@code LevelRenderEvents.BEFORE_GIZMOS}, как
 * подсветка сундуков и проверки слоя, — поэтому видна и там, где Litematica голограмм не строит
 * (незагруженные чанки, перетаскивание без «голограмм на лету»).
 */
@Environment(EnvType.CLIENT)
public final class PlacementOverlay
{
    private static final float OUTLINE_W = 2.5F;
    private static final float CORNER_W = 2.0F;
    private static final float GRID_W = 1.5F;

    private static final float A_CORNER_FILL = 0.35F;
    private static final float A_SHADOW_FILL = 0.18F;
    private static final float A_SHADOW_EDGE = 0.7F;
    private static final float A_GRID = 0.75F;
    private static final float A_HANDLE_FILL = 0.55F;
    private static final float A_HANDLE_HOT_FILL = 0.85F;
    private static final float HANDLE_W = 1.5F;

    /** Ручка угла — кубик со стороной в столько пикселей GUI на экране (подсвеченная — крупнее). */
    private static final double HANDLE_PX = 6.0;
    private static final double HANDLE_HOT_PX = 9.0;

    /** Отрыв от поверхностей, чтобы линии не мерцали в гранях блоков. */
    private static final double LIFT = 0.02;

    /** Сетку шире стольких линий по оси не рисуем: на огромной схеме она — сплошная штриховка. */
    private static final int GRID_MAX_LINES = 96;

    private static boolean registered;
    private static @Nullable PlacementEditorScreen editor;

    private PlacementOverlay() {}

    /**
     * Подписка на кадр мира. Штатно зовётся из {@code SchematicsModule.init()}; повторный вызов
     * ничего не делает (редактор зовёт его ещё раз при открытии — страховка от пропущенной проводки).
     */
    public static void register()
    {
        if (registered)
        {
            return;
        }

        registered = true;
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> render());

        // Обводка блока под прицелом игрока в виде сверху и в облёте — лишний чёрный квадрат где-то внизу
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> ! TopViewCamera.active()
                && ! OrbitCamera.active());
    }

    static void attach(PlacementEditorScreen screen)
    {
        editor = screen;
    }

    static void detach(PlacementEditorScreen screen)
    {
        if (editor == screen)
        {
            editor = null;
        }
    }

    private static void render()
    {
        PlacementEditorScreen screen = editor;
        Minecraft mc = Minecraft.getInstance();

        if (screen == null || mc.level == null || mc.screen != screen)
        {
            return;
        }

        PlacementOps.Bounds b = screen.previewBounds();

        if (b == null)
        {
            return;
        }

        int color = screen.movingSchematic() ? Theme.YELLOW : screen.frameLocked() ? Theme.GOLD : Theme.BLUE;

        Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1.0, b.maxY() + 1.0, b.maxZ() + 1.0)
                .inflate(LIFT), GizmoStyle.stroke(color, OUTLINE_W)).setAlwaysOnTop();

        // Мин. угол — опора сетки чанков и «к земле»; после поворота origin Litematica в другом углу
        Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.minX() + 1.0, b.minY() + 1.0, b.minZ() + 1.0)
                .inflate(LIFT * 2.0), GizmoStyle.strokeAndFill(color, CORNER_W, Theme.alpha(color, A_CORNER_FILL)))
                .setAlwaysOnTop();

        if (screen.cornerHandles())
        {
            handles(screen, b, color);
        }

        shadow(mc.level, b, color);

        if (screen.chunkSnap())
        {
            chunkGrid(b);
        }
    }

    /**
     * Проекция контура на землю: плоскость на высоте поверхности под центром габарита и отвесы от
     * нижних углов. Только над загруженной землёй — где её нет, показывать нечего.
     */
    private static void shadow(ClientLevel level, PlacementOps.Bounds b, int color)
    {
        int cx = Math.floorDiv(b.minX() + b.maxX(), 2);
        int cz = Math.floorDiv(b.minZ() + b.maxZ(), 2);
        LevelChunk chunk = level.getChunkSource().getChunk(cx >> 4, cz >> 4, ChunkStatus.FULL, false);

        if (chunk == null)
        {
            return;
        }

        double ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) + 1.0 + LIFT;

        if (Math.abs(ground - b.minY()) < 0.5)
        {
            return;
        }

        double x0 = b.minX();
        double z0 = b.minZ();
        double x1 = b.maxX() + 1.0;
        double z1 = b.maxZ() + 1.0;
        int edge = Theme.alpha(color, A_SHADOW_EDGE);

        Gizmos.rect(new Vec3(x0, ground, z0), new Vec3(x1, ground, z0), new Vec3(x1, ground, z1),
                new Vec3(x0, ground, z1), GizmoStyle.strokeAndFill(edge, CORNER_W, Theme.alpha(color, A_SHADOW_FILL)));

        double top = b.minY();

        Gizmos.line(new Vec3(x0, top, z0), new Vec3(x0, ground, z0), edge, CORNER_W);
        Gizmos.line(new Vec3(x1, top, z0), new Vec3(x1, ground, z0), edge, CORNER_W);
        Gizmos.line(new Vec3(x1, top, z1), new Vec3(x1, ground, z1), edge, CORNER_W);
        Gizmos.line(new Vec3(x0, top, z1), new Vec3(x0, ground, z1), edge, CORNER_W);
    }

    /**
     * Ручки подъёма на углах: размер — в пикселях экрана, а не в блоках (на глубине угла), иначе у
     * далёкой схемы их не видно, а у близкой они закрывают полсхемы. Угол за камерой — без ручки.
     */
    private static void handles(PlacementEditorScreen screen, PlacementOps.Bounds b, int color)
    {
        int hot = screen.highlightedCorner();

        for (int i = 0; i < PlacementOps.Bounds.CORNERS; ++i)
        {
            Vec3 corner = b.corner(i);
            double[] at = CursorRay.project(corner, screen.width, screen.height);

            if (at == null)
            {
                continue;
            }

            boolean lit = i == hot;
            double half = CursorRay.blocksPerPixel(at[2], screen.height) * (lit ? HANDLE_HOT_PX : HANDLE_PX) * 0.5;
            int edge = lit ? Ui.C_GOLD : color;
            int fill = Theme.alpha(edge, lit ? A_HANDLE_HOT_FILL : A_HANDLE_FILL);

            Gizmos.cuboid(new AABB(corner.x - half, corner.y - half, corner.z - half, corner.x + half, corner.y + half,
                    corner.z + half), GizmoStyle.strokeAndFill(edge, lit ? CORNER_W : HANDLE_W, fill)).setAlwaysOnTop();
        }
    }

    /** Границы чанков под габаритом (с запасом в один чанк) на высоте его низа. */
    private static void chunkGrid(PlacementOps.Bounds b)
    {
        int fromX = (Math.floorDiv(b.minX(), 16) - 1) * 16;
        int toX = (Math.floorDiv(b.maxX(), 16) + 2) * 16;
        int fromZ = (Math.floorDiv(b.minZ(), 16) - 1) * 16;
        int toZ = (Math.floorDiv(b.maxZ(), 16) + 2) * 16;

        if ((toX - fromX) / 16 > GRID_MAX_LINES || (toZ - fromZ) / 16 > GRID_MAX_LINES)
        {
            return;
        }

        double y = b.minY() + LIFT;
        int color = Theme.alpha(Theme.CYAN, A_GRID);

        for (int x = fromX; x <= toX; x += 16)
        {
            Gizmos.line(new Vec3(x, y, fromZ), new Vec3(x, y, toZ), color, GRID_W).setAlwaysOnTop();
        }

        for (int z = fromZ; z <= toZ; z += 16)
        {
            Gizmos.line(new Vec3(fromX, y, z), new Vec3(toX, y, z), color, GRID_W).setAlwaysOnTop();
        }
    }
}
