package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.PlacementCursorScreen;
import com.aell.layerlist.schematics.gui.PlacementHud;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import java.util.ArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;

/**
 * Режим {@code placeworld}: редактор размещения — замок, акценты кнопок и «Смотреть на мир».
 *
 * <p>Настоящими кликами и клавишами (MouseHandler, KeyboardHandler, события SDL для R): редактор открыт на тестовом
 * размещении — снимок панели (замок открыт, акценты); в поле X набрано число (Enter) — origin изменился; замок нажат —
 * размещение закреплено, снимок; «+» X закреплённого размещения — origin не изменился, поле X серое; «Смотреть на мир» —
 * экран закрыт, {@code PlacementHud.active()}, HUD игры вернулся, снимок карточки; R — курсор у карточки (экран
 * {@code PlacementCursorScreen}), снимок; замок на карточке снят, «+» Y на карточке — origin Y на 1 больше; Esc — тот же
 * редактор снова на экране (HUD снова спрятан); замок нажат ещё раз и «Отмена» — origin, поворот, отражение и замок
 * такие же, как при открытии.
 */
@Environment(EnvType.CLIENT)
public final class PlaceWorldTest implements CovJob
{
    private static final String TAG = "[placeworld] ";

    private int stage;
    private long nextAt;
    private boolean failed;
    private SchematicPlacement placement;
    private PlacementEditorScreen editor;
    private BlockPos startOrigin = BlockPos.ZERO;
    private Rotation startRotation = Rotation.NONE;
    private Mirror startMirror = Mirror.NONE;
    private boolean startLocked;
    private int typedX;
    private int yBefore;

    @Override
    public String name()
    {
        return "PLACEWORLD (редактор размещения: замок, акценты, «Смотреть на мир» → карточка → R → Esc → «Отмена»)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        if (mc.player == null || mc.level == null)
        {
            return false;
        }

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
            switch (this.stage++)
            {
                case 0 ->
                {
                    this.prepare(mc);
                    PlacementEditorScreen.open(null, this.placement, false, false);
                    this.nextAt = Util.getMillis() + 2500L;
                }
                case 1 ->
                {
                    this.editor = PlacementEditorScreen.TestHooks.current(mc.gui.screen());
                    this.check("редактор открыт", this.editor != null);
                    this.check("HUD игры спрятан на время редактора", mc.gui.hud.isHidden());
                    this.check("замок при открытии открыт", ! this.placement.isLocked());
                    SupportTest.moveMouse(mc, 20, mc.getWindow().getGuiScaledHeight() / 2.0);
                    this.nextAt = Util.getMillis() + 600L;
                }
                case 2 ->
                {
                    CovOut.log(TAG + "снимок 1: панель, замок открыт, акценты кнопок");
                    Screenshot.grab(mc, false);
                    this.typedX = this.startOrigin.getX() + 7;
                    int[] field = PlacementEditorScreen.TestHooks.fieldBounds(this.editor, 0);
                    SupportTest.click(mc, field[0] + field[2] / 2.0, field[1] + field[3] / 2.0);
                    this.nextAt = Util.getMillis() + 250L;
                }
                case 3 ->
                {
                    this.check("поле X в вводе", PlacementEditorScreen.TestHooks.editingAxis(this.editor) == 0);

                    for (char c : Integer.toString(this.typedX).toCharArray())
                    {
                        mc.keyboardHandler.textInput(mc.getWindow().handle(), String.valueOf(c));
                    }

                    this.key(mc, InputConstants.KEY_RETURN, '\r');
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 4 ->
                {
                    this.check("число в поле X применено: " + this.startOrigin.getX() + " → " + this.placement.getOrigin().getX(),
                            this.placement.getOrigin().getX() == this.typedX);
                    this.click(mc, PlacementEditorScreen.TestHooks.lockButton(this.editor));
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 5 ->
                {
                    this.check("замок нажат — размещение закреплено", this.placement.isLocked());
                    this.check("поле X серое у закреплённого", ! PlacementEditorScreen.TestHooks.fieldEnabled(this.editor, 0));
                    this.check("«−»/«+» серые у закреплённого", ! PlacementEditorScreen.TestHooks.stepEnabled(this.editor, 0));
                    SupportTest.moveMouse(mc, 20, mc.getWindow().getGuiScaledHeight() / 2.0);
                    this.nextAt = Util.getMillis() + 500L;
                }
                case 6 ->
                {
                    CovOut.log(TAG + "снимок 2: замок закрыт, элементы серые");
                    Screenshot.grab(mc, false);
                    this.click(mc, PlacementEditorScreen.TestHooks.stepButton(this.editor, 0, 1));
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 7 ->
                {
                    this.check("«+» X закреплённого — origin не изменился", this.placement.getOrigin().getX() == this.typedX);
                    this.check("отказ сказан в панели", PlacementEditorScreen.TestHooks.message(this.editor) != null);
                    this.click(mc, PlacementEditorScreen.TestHooks.worldButton(this.editor));
                    this.nextAt = Util.getMillis() + 900L;
                }
                case 8 ->
                {
                    this.check("«Смотреть на мир» — экран закрыт", mc.gui.screen() == null);
                    this.check("PlacementHud.active()", PlacementHud.active());
                    this.check("HUD игры вернулся", ! mc.gui.hud.isHidden());
                    this.check("мышь захвачена игрой", mc.mouseHandler.isMouseGrabbed());
                    this.nextAt = Util.getMillis() + 500L;
                }
                case 9 ->
                {
                    CovOut.log(TAG + "снимок 3: игра, карточка размещения HUD-ом сверху справа");
                    Screenshot.grab(mc, false);
                    this.pushR();
                    this.nextAt = Util.getMillis() + 500L;
                }
                case 10 ->
                {
                    this.check("R — курсор у карточки (PlacementCursorScreen)", mc.gui.screen() instanceof PlacementCursorScreen);
                    this.check("мышь свободна", ! mc.mouseHandler.isMouseGrabbed());
                    SupportTest.moveMouse(mc, mc.getWindow().getGuiScaledWidth() * 0.3, mc.getWindow().getGuiScaledHeight() * 0.6);
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 11 ->
                {
                    CovOut.log(TAG + "снимок 4: курсор у карточки, замок закрыт");
                    Screenshot.grab(mc, false);
                    this.click(mc, PlacementHud.TestHooks.lock(mc.getWindow().getGuiScaledWidth()));
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 12 ->
                {
                    this.check("замок на карточке снят", ! this.placement.isLocked());
                    this.yBefore = this.placement.getOrigin().getY();
                    this.click(mc, PlacementHud.TestHooks.nudgePlus(mc.getWindow().getGuiScaledWidth(), 1));
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 13 ->
                {
                    this.check("«+» Y на карточке: Y " + this.yBefore + " → " + this.placement.getOrigin().getY(),
                            this.placement.getOrigin().getY() == this.yBefore + 1);
                    CovOut.log(TAG + "снимок 5: карточка после «+» Y");
                    Screenshot.grab(mc, false);
                    this.key(mc, InputConstants.KEY_ESCAPE, 0x1B);
                    this.nextAt = Util.getMillis() + 700L;
                }
                case 14 ->
                {
                    PlacementEditorScreen back = PlacementEditorScreen.TestHooks.current(mc.gui.screen());
                    this.check("Esc на карточке — снова редактор", back != null);
                    this.check("это тот же экземпляр редактора", back == this.editor);
                    this.check("PlacementHud больше не active", ! PlacementHud.active());
                    this.check("HUD игры снова спрятан", mc.gui.hud.isHidden());
                    this.check("вид сверху заново не включился", "NORMAL".equals(PlacementEditorScreen.TestHooks.view(this.editor)));
                    this.click(mc, PlacementEditorScreen.TestHooks.lockButton(this.editor));
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 15 ->
                {
                    this.check("замок снова закрыт перед «Отмена»", this.placement.isLocked());
                    this.click(mc, PlacementEditorScreen.TestHooks.footerButton(this.editor, false));
                    this.nextAt = Util.getMillis() + 700L;
                }
                case 16 ->
                {
                    this.check("«Отмена» — редактор закрыт", mc.gui.screen() == null);
                    this.check("origin как при открытии: " + this.placement.getOrigin().toShortString(),
                            this.placement.getOrigin().equals(this.startOrigin));
                    this.check("поворот как при открытии", this.placement.getRotation() == this.startRotation);
                    this.check("отражение как при открытии", this.placement.getMirror() == this.startMirror);
                    this.check("замок как при открытии (" + this.startLocked + ")", this.placement.isLocked() == this.startLocked);
                    this.check("HUD игры вернулся", ! mc.gui.hud.isHidden());
                    this.cleanup();
                    CovOut.log(TAG + "итог: " + (this.failed ? "FAIL" : "PASS"));
                    return true;
                }
                default ->
                {
                    return true;
                }
            }
        }
        catch (RuntimeException | LinkageError e)
        {
            this.check("сбой: " + e, false);
            CovOut.log(TAG + "итог: FAIL");
            this.cleanup();
            mc.gui.setScreen(null);
            return true;
        }

        return false;
    }

    /** Тестовое размещение перед игроком, снимок его начального состояния. */
    private void prepare(Minecraft mc)
    {
        this.cleanup();

        for (SchematicPlacement old : new ArrayList<>(DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(old);
        }

        BlockPos at = mc.player.blockPosition();
        BlockPos a = at.offset(4, -1, 4);
        AreaSelection area = new AreaSelection();
        area.setName("dev-placeworld");
        area.addSubRegionBox(new Box(a, a.offset(7, 4, 7), "main"), false);
        area.setExplicitOrigin(a);
        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(mc.level, area,
                new LitematicaSchematic.SchematicSaveInfo(false, false, true, false), "dev", CovOut::log);

        this.placement = SchematicPlacement.createFor(schematic, at.offset(6, 0, 6), "dev-placeworld", true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(this.placement, false);
        this.startOrigin = this.placement.getOrigin();
        this.startRotation = this.placement.getRotation();
        this.startMirror = this.placement.getMirror();
        this.startLocked = this.placement.isLocked();
        mc.player.setYRot(-35.0F);
        mc.player.setXRot(25.0F);
        CovOut.log(TAG + "размещение " + this.startOrigin.toShortString() + ", замок " + this.startLocked);
    }

    private void cleanup()
    {
        if (this.placement != null && DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().contains(this.placement))
        {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(this.placement);
        }

        PlacementHud.leave();
    }

    private void click(Minecraft mc, int[] box)
    {
        SupportTest.click(mc, box[0] + box[2] / 2.0, box[1] + box[3] / 2.0);
    }

    /** Клавиша через KeyboardHandler — тот же путь, что у настоящей. */
    private void key(Minecraft mc, int key, int code)
    {
        KeyEvent event = new KeyEvent(key, code, 0);
        mc.keyboardHandler.keyPress(mc.getWindow().handle(), InputConstants.PRESS, event);
        mc.keyboardHandler.keyPress(mc.getWindow().handle(), InputConstants.RELEASE, event);
    }

    /** R — событием в очереди SDL, как от клавиатуры: хоткеи malilib 0.30 узнают зажатые клавиши только из него. */
    private void pushR()
    {
        long window = Minecraft.getInstance().getWindow().handle();
        int windowId = SDLVideo.SDL_GetWindowID(window);

        for (boolean down : new boolean[] { true, false })
        {
            SDL_Event event = SDL_Event.calloc();

            try
            {
                event.key(key -> key.type(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP).windowID(windowId)
                        .scancode(InputConstants.KEY_R).key('r').down(down).repeat(false));
                SDLEvents.SDL_PushEvent(event);
            }
            finally
            {
                event.free();
            }
        }
    }

    private void check(String what, boolean ok)
    {
        CovOut.log(TAG + (ok ? "OK   " : "FAIL ") + what);
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
