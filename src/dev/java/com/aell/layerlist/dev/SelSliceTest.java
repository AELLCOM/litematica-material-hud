package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.area.AreaCapture;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.library.VoxelSnapshot;
import com.aell.layerlist.schematics.preview.PreviewWidget;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.ui.kit.Slider;
import com.aell.layerlist.ui.kit.Widget;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import java.lang.reflect.Field;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Blocks;

/**
 * Режим {@code selslice}: срез превью «Выделения» по слоям выделения и квадратная кнопка «+/−».
 *
 * <p>Строит рядом башню 3×6×3 — на каждом слое свой блок, над ней и сбоку лишние блоки, — выделяет ровно башню и
 * открывает вкладку. Проверяет: ползунок «Срез» — по слоям выделения (0…6, справа — всё) и живой; кнопки «+/−» —
 * квадраты; колесом над ползунком — на слой 3 из 6: превью показывает снимок ровно коробки (3×6×3, клетка 1 блок, без
 * лишних блоков), срез — на этом слое; колесом обратно вправо — снова вся область без среза. Снимки: срез, всё.
 */
@Environment(EnvType.CLIENT)
public final class SelSliceTest implements CovJob
{
    private static final net.minecraft.world.level.block.Block[] LAYERS = { Blocks.STONE, Blocks.OAK_PLANKS, Blocks.GLASS,
            Blocks.BRICKS, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK };

    private int stage;
    private long nextAt;
    private long since;
    private boolean failed;
    private BlockPos base = BlockPos.ZERO;

    @Override
    public String name()
    {
        return "SELSLICE (срез превью по слоям выделения, кнопка «+/−»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        MinecraftServer server = mc.getSingleplayerServer();

        if (server == null || mc.player == null)
        {
            return false;
        }

        this.base = mc.player.blockPosition().offset(4, 0, 4);
        BlockPos b = this.base;
        server.execute(() ->
        {
            var level = server.overworld();

            for (int y = 0; y < LAYERS.length; ++y)
            {
                for (int x = 0; x < 3; ++x)
                {
                    for (int z = 0; z < 3; ++z)
                    {
                        level.setBlockAndUpdate(b.offset(x, y, z), LAYERS[y].defaultBlockState());
                    }
                }
            }

            // Лишнее: над коробкой и вплотную сбоку — в срез попасть не должно
            level.setBlockAndUpdate(b.offset(1, LAYERS.length, 1), Blocks.GLOWSTONE.defaultBlockState());
            level.setBlockAndUpdate(b.offset(-1, 2, 1), Blocks.EMERALD_BLOCK.defaultBlockState());
        });

        SelectionManager manager = DataManager.getSelectionManager();

        if (manager.getSelectionMode() != SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();
        Box box = selection.getSelectedSubRegionBox();

        if (box == null)
        {
            selection.createNewSubRegionBox(b, "selslice");
            box = selection.getSelectedSubRegionBox();
        }

        selection.setSubRegionCornerPos(box, Corner.CORNER_1, b);
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, b.offset(2, LAYERS.length - 1, 2));
        CovOut.log("[selslice] башня " + b.toShortString() + " … " + b.offset(2, LAYERS.length - 1, 2).toShortString());
        this.nextAt = Util.getMillis() + 1500L;
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
        if (Util.getMillis() < this.nextAt)
        {
            return false;
        }

        try
        {
            return this.run(mc);
        }
        catch (RuntimeException | LinkageError | ReflectiveOperationException e)
        {
            CovOut.log("[selslice] FAIL сбой: " + e);
            mc.setScreen(null);
            return true;
        }
    }

    private boolean run(Minecraft mc) throws ReflectiveOperationException
    {
        long now = Util.getMillis();

        switch (this.stage)
        {
            case 0 ->
            {
                GuiSchematics.openSelection(null);
                this.since = now;
                this.stage = 1;
            }
            case 1 ->
            {
                Object pane = this.pane(mc);

                if (field(pane, "area") == null)
                {
                    if (now - this.since > 120_000L)
                    {
                        this.check("снимок области за 120 с", false);
                        this.stage = 9;
                    }

                    break;
                }

                Slider cut = (Slider) field(pane, "cut");
                this.check("ползунок «Срез» — по слоям выделения: 0…" + cut.max() + ", стоит на " + cut.value(),
                        cut.min() == 0 && cut.max() == LAYERS.length && cut.value() == LAYERS.length);
                this.check("ползунок живой", cut.enabled());

                Widget[] plus = (Widget[]) field(field(pane, "corner1"), "plus");
                Widget n = plus[0];
                this.check("кнопка «+/−» квадратная: " + n.w() + "×" + n.h(), n.w() == n.h() && n.w() >= 17);

                // Колесом над ползунком — на слой 3 (номер 2): как игрок
                double x = cut.x() + cut.w() / 2.0;
                double y = cut.y() + cut.h() / 2.0;

                for (int i = 0; i < LAYERS.length - 2; ++i)
                {
                    mc.screen.mouseScrolled(x, y, 0.0, -1.0);
                }

                this.check("колесо: слой " + cut.value(), cut.value() == 2);
                this.since = now;
                this.stage = 2;
            }
            case 2 ->
            {
                Object pane = this.pane(mc);
                PreviewWidget preview = (PreviewWidget) field(pane, "preview");
                AreaCapture.Result slice = (AreaCapture.Result) field(pane, "slice");

                if (slice == null || ! preview.hasModel() || field(preview, "source") != slice.snapshot())
                {
                    if (now - this.since > 30_000L)
                    {
                        this.check("срез готов за 30 с (снимок " + (slice != null) + ", модель " + preview.hasModel() + ")", false);
                        this.stage = 9;
                    }

                    break;
                }

                VoxelSnapshot s = slice.snapshot();
                this.check("снимок среза — ровно коробка: " + s.sizeX() + "×" + s.sizeY() + "×" + s.sizeZ() + ", клетка " + slice.scale(),
                        s.sizeX() == 3 && s.sizeY() == LAYERS.length && s.sizeZ() == 3 && slice.scale() == 1);
                this.check("угол снимка — угол выделения: " + slice.origin().toShortString(), slice.origin().equals(this.base));
                boolean layers = true;

                for (int y = 0; y < LAYERS.length; ++y)
                {
                    layers &= s.get(1, y, 1).is(LAYERS[y]);
                }

                this.check("слои снимка — блоки башни снизу вверх", layers);
                this.check("лишнего сбоку нет: " + s.nonAir() + " непустых из " + 3 * 3 * LAYERS.length, s.nonAir() == 3 * 3 * LAYERS.length);
                this.check("срез превью — на слое 2: " + preview.layerCut(), preview.layerCut() == 2);
                this.check("пока режем — рисует своё превью, не Voxy", ! (boolean) field(pane, "voxyShown"));
                CovOut.log("[selslice] снимок 1: срез на слое 3 из 6");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                this.nextAt = now + 400L;
                this.stage = 3;
            }
            case 3 ->
            {
                Object pane = this.pane(mc);
                Slider cut = (Slider) field(pane, "cut");
                double x = cut.x() + cut.w() / 2.0;
                double y = cut.y() + cut.h() / 2.0;

                for (int i = 0; i < LAYERS.length; ++i)
                {
                    mc.screen.mouseScrolled(x, y, 0.0, 1.0);
                }

                this.check("колесо вправо до упора — «всё»: " + cut.value(), cut.value() == cut.max());
                this.since = now;
                this.stage = 4;
            }
            case 4 ->
            {
                Object pane = this.pane(mc);
                PreviewWidget preview = (PreviewWidget) field(pane, "preview");
                AreaCapture.Result area = (AreaCapture.Result) field(pane, "area");

                // Справа — вся область: своё превью или, если стоит, снова Voxy
                boolean voxy = (boolean) field(pane, "voxyShown");

                if (! voxy && (field(preview, "source") != area.snapshot() || ! preview.hasModel()))
                {
                    if (now - this.since > 30_000L)
                    {
                        this.check("снова вся область за 30 с", false);
                        this.stage = 9;
                    }

                    break;
                }

                this.check("справа — снова вся область" + (voxy ? " (рисует Voxy)" : ""), true);
                this.check("без среза: " + preview.layerCut(), preview.layerCut() == Integer.MAX_VALUE);
                CovOut.log("[selslice] снимок 2: вся область");
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), message -> {});
                this.nextAt = now + 400L;
                this.stage = 9;
            }
            default ->
            {
                mc.setScreen(null);
                Nav.clear();
                CovOut.log("[selslice] итог: " + (this.failed ? "FAIL" : "OK"));
                return true;
            }
        }

        if (this.nextAt <= now)
        {
            this.nextAt = now + 100L;
        }

        return false;
    }

    private Object pane(Minecraft mc) throws ReflectiveOperationException
    {
        return field(mc.screen, "selection");
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Class<?> type = owner.getClass();

        while (type != null)
        {
            try
            {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            }
            catch (NoSuchFieldException e)
            {
                type = type.getSuperclass();
            }
        }

        throw new NoSuchFieldException(name);
    }

    private void check(String what, boolean ok)
    {
        CovOut.log("[selslice] " + (ok ? "OK   " : "FAIL ") + what);
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
