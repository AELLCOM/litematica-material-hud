package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.SchematicsConfig;
import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.SelectionHud;
import com.aell.layerlist.shaded.ui.Nav;
import com.aell.layerlist.ui.kit.Widget;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionManager;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.PositionUtils.Corner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;

/**
 * Режим {@code selworld}: вкладка «Выделение» — карточка координат сверху справа, кнопки шага «+ / −» и «Смотреть на
 * мир» с переходом в игру.
 *
 * <p>Настоящими кликами и клавишами (MouseHandler, KeyboardHandler): «+» / «−» / ПКМ по «+» у X угла 1; «Смотреть на
 * мир» и Esc, пока панели едут, — отмена; «Смотреть на мир» — экран закрыт, мышь захвачена, HUD включён; в игре W
 * двигает игрока, ЛКМ ломает блок под прицелом; R — курсор у карточки на месте HUD: «+», ввод числа в поле Y, щелчок
 * мимо — снова игра, R/R — туда и обратно; Esc — снова «Выделение» (меню паузы не открылось, HUD выключен); Esc —
 * экран закрыт; Esc в игре — обычная пауза. Ещё: «Смотреть на мир» в верхней строке, «Простое / Составное» нет на
 * вкладке, опция «Составное выделение» (по умолчанию выкл) переключает режим Litematica.
 * Снимки: экран, выезд, игра с HUD, курсор у карточки, въезд обратно, экран.
 */
@Environment(EnvType.CLIENT)
public final class SelWorldTest implements CovJob
{
    /** Порядок шагов: проверки опции и курсора вставлены номерами 100+ и 200+. */
    private static final int[] ORDER = { 0, 1, 2, 3, 4, 5, 100, 101, 6, 7, 8, 9, 10, 11, 12, 13, 200, 201, 202, 203, 210, 211, 212,
            204, 205, 206, 207, 14, 15, 16, 17, 18, -1 };
    private int stage;
    private long nextAt;
    private boolean failed;
    private int x0;
    private Vec3 walkFrom = Vec3.ZERO;
    private BlockPos target = BlockPos.ZERO;

    @Override
    public String name()
    {
        return "SELWORLD (вкладка «Выделение»: карточка координат, «+ / −», «Смотреть на мир» → игра → Esc)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
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
            int at = ORDER[Math.min(this.stage++, ORDER.length - 1)];

            switch (at)
            {
                case 0 ->
                {
                    this.prepare(mc);
                    GuiSchematics.openSelection(null);
                    this.nextAt = Util.getMillis() + 4000L;
                }
                case 1 ->
                {
                    this.check("экран «Выделение» открыт", mc.gui.screen() instanceof GuiSchematics);
                    Widget plus = this.nudge(mc, 0);
                    SupportTest.moveMouse(mc, plus.x() + plus.w() / 2.0, plus.y() + 3);
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 2 ->
                {
                    CovOut.log("[selworld] снимок 1: новая раскладка, курсор на «+» X угла 1");
                    Screenshot.grab(mc, false);
                    Widget eye = (Widget) field(this.pane(mc), "seeWorld");
                    Widget all = (Widget) field(this.pane(mc), "allSelections");
                    Widget n = this.nudge(mc, 0);
                    this.check("«Смотреть на мир» в верхней строке, левее «Все выделения…»", eye.y() == all.y() && eye.x() + eye.w() <= all.x()
                            && eye.y() < n.y());
                    this.check("кнопок «Простое / Составное» на вкладке нет", ! hasField(this.pane(mc), "simple") && ! hasField(this.pane(mc), "normal"));
                    this.check("«Составное выделение» по умолчанию выкл → простое", ! SchematicsConfig.SELECTION_COMPOUND.getDefaultBooleanValue()
                            && DataManager.getSelectionManager().getSelectionMode() == SelectionMode.SIMPLE);
                    this.x0 = this.cornerX();
                    SupportTest.click(mc, n.x() + n.w() / 2.0, n.y() + 3);
                    this.nextAt = Util.getMillis() + 200L;
                }
                case 3 ->
                {
                    this.check("«+» (верхняя половина): X " + this.x0 + " → " + this.cornerX(), this.cornerX() == this.x0 + 1);
                    Widget n = this.nudge(mc, 0);
                    SupportTest.click(mc, n.x() + n.w() / 2.0, n.y() + n.h() - 3);
                    this.nextAt = Util.getMillis() + 200L;
                }
                case 4 ->
                {
                    this.check("«−» (нижняя половина): X вернулся к " + this.x0, this.cornerX() == this.x0);
                    Widget n = this.nudge(mc, 0);
                    SupportTest.moveMouse(mc, n.x() + n.w() / 2.0, n.y() + 3);
                    this.mouse(mc, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.PRESS);
                    this.mouse(mc, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.RELEASE);
                    this.nextAt = Util.getMillis() + 200L;
                }
                case 5 ->
                {
                    this.check("ПКМ по «+» — обратный шаг: X " + (this.x0 - 1), this.cornerX() == this.x0 - 1);
                    SchematicsConfig.SELECTION_COMPOUND.setBooleanValue(true);
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 100 ->
                {
                    this.check("опция «Составное» вкл → режим Litematica NORMAL",
                            DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL);
                    SchematicsConfig.SELECTION_COMPOUND.setBooleanValue(false);
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 101 ->
                {
                    this.check("опция «Составное» выкл → снова SIMPLE, выделение то же: X " + this.cornerX(),
                            DataManager.getSelectionManager().getSelectionMode() == SelectionMode.SIMPLE && this.cornerX() == this.x0 - 1);
                    this.clickEye(mc);
                    this.nextAt = Util.getMillis() + 60L;
                }
                case 6 ->
                {
                    this.check("кнопка запустила выезд", this.moving(mc));
                    this.key(mc, InputConstants.KEY_ESCAPE, 0x1B);
                    this.nextAt = Util.getMillis() + 900L;
                }
                case 7 ->
                {
                    this.check("Esc во время выезда — отмена: экран на месте, панели вернулись",
                            mc.gui.screen() instanceof GuiSchematics && ! this.moving(mc) && ! SelectionHud.active());
                    this.clickEye(mc);
                    this.nextAt = Util.getMillis() + 120L;
                }
                case 8 ->
                {
                    CovOut.log("[selworld] снимок 2: выезд в мир");
                    Screenshot.grab(mc, false);
                    this.nextAt = Util.getMillis() + 1200L;
                }
                case 9 ->
                {
                    this.check("доехали — экран закрыт", mc.gui.screen() == null);
                    this.check("HUD «Смотреть на мир» включён", SelectionHud.active());
                    this.check("мышь захвачена игрой", mc.mouseHandler.isMouseGrabbed());
                    // Не летать: дальше ломаем блок под ногами, до него должна доставать рука
                    mc.player.getAbilities().flying = false;
                    this.walkFrom = mc.player.position();
                    mc.options.keyUp.setDown(true);
                    this.nextAt = Util.getMillis() + 700L;
                }
                case 10 ->
                {
                    mc.options.keyUp.setDown(false);
                    double walked = mc.player.position().distanceTo(this.walkFrom);
                    this.check(String.format("W в игре двигает игрока: %.2f блока", walked), walked > 0.5);
                    // Назад на площадку (вперёд мог уйти с края) и взгляд под ноги
                    mc.player.setPos(this.walkFrom.x, this.walkFrom.y, this.walkFrom.z);
                    mc.player.setDeltaMovement(Vec3.ZERO);
                    mc.player.setXRot(90.0F);
                    this.nextAt = Util.getMillis() + 1200L;
                }
                case 11 ->
                {
                    HitResult hit = mc.hitResult;
                    boolean aimed = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                            && ! mc.level.getBlockState(block.getBlockPos()).isAir();
                    this.check("прицел на блоке (" + (hit == null ? "нет" : hit.getType()) + ", игрок "
                            + mc.player.blockPosition().toShortString() + ", под ногами " + mc.level.getBlockState(mc.player.blockPosition().below())
                            + ")", aimed);
                    this.target = aimed ? ((BlockHitResult) hit).getBlockPos() : BlockPos.ZERO;
                    CovOut.log("[selworld] ломаем " + mc.level.getBlockState(this.target) + " в " + this.target.toShortString()
                            + (mc.player.isCreative() ? " (творчество)" : " (выживание)"));
                    this.mouse(mc, InputConstants.MOUSE_BUTTON_LEFT, InputConstants.PRESS);
                    this.nextAt = Util.getMillis() + (mc.player.isCreative() ? 300L : 3000L);
                }
                case 12 ->
                {
                    this.mouse(mc, InputConstants.MOUSE_BUTTON_LEFT, InputConstants.RELEASE);
                    this.check("ЛКМ в игре сломал блок", mc.level.getBlockState(this.target).isAir());
                    mc.player.setXRot(25.0F);
                    this.nextAt = Util.getMillis() + 600L;
                }
                case 13 ->
                {
                    CovOut.log("[selworld] снимок 3: игра, координаты HUD-ом сверху справа");
                    Screenshot.grab(mc, false);
                    this.pushR();
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 200 ->
                {
                    this.check("R в игре — курсор у карточки (экран «Выделение», карточка живая)",
                            mc.gui.screen() instanceof GuiSchematics && this.parked(mc) && ! SelectionHud.active());
                    this.check("меню паузы не открылось, мышь свободна", ! mc.mouseHandler.isMouseGrabbed());
                    this.x0 = this.cornerX();
                    Widget n = this.nudge(mc, 0);
                    this.check("карточка на месте HUD (сверху справа)", n.y() < 80 && n.x() > mc.getWindow().getGuiScaledWidth() / 2);
                    SupportTest.click(mc, n.x() + n.w() / 2.0, n.y() + 3);
                    this.nextAt = Util.getMillis() + 200L;
                }
                case 201 ->
                {
                    this.check("«+» при курсоре: X " + this.x0 + " → " + this.cornerX(), this.cornerX() == this.x0 + 1);
                    Widget y = this.field(mc, 1);
                    SupportTest.click(mc, y.x() + y.w() / 2.0, y.y() + y.h() / 2.0);
                    this.nextAt = Util.getMillis() + 150L;
                }
                case 202 ->
                {
                    // Стереть старое число (курсор в конце поля) и набрать новое
                    for (int i = 0; i < 6; ++i)
                    {
                        this.key(mc, InputConstants.KEY_BACKSPACE, '\b');
                    }

                    mc.keyboardHandler.textInput(mc.getWindow().handle(), "9");
                    mc.keyboardHandler.textInput(mc.getWindow().handle(), "0");
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 203 ->
                {
                    int y = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox().getPos1().getY();
                    this.check("ввод в поле Y при курсоре: Y = " + y, y == 90);
                    Widget n = this.nudge(mc, 0);
                    SupportTest.moveMouse(mc, n.x() + n.w() / 2.0, n.y() + n.h() - 3);
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 210 ->
                {
                    // «Сбросить» на самой карточке (в мире верхней строки экрана нет)
                    Widget reset = (Widget) field(this.pane(mc), "resetCard");
                    this.check("на карточке в мире есть «Сбросить»", reset.visible() && reset.enabled());
                    SupportTest.click(mc, reset.x() + reset.w() / 2.0, reset.y() + reset.h() / 2.0);
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 211 ->
                {
                    CovOut.log("[selworld] снимок 3в: подтверждение сброса поверх мира");
                    Screenshot.grab(mc, false);
                    this.key(mc, InputConstants.KEY_RETURN, '\r');
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 212 ->
                {
                    Box box = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox();
                    boolean cleared = box == null || (box.getPos1() == null && box.getPos2() == null);
                    Object origin = field(this.pane(mc), "origin");
                    String originX = ((com.aell.layerlist.ui.kit.TextInput) ((Widget[]) field(origin, "fields"))[0]).text();
                    this.check("«Сбросить» с карточки (Enter — подтвердить): углы стёрты", cleared);
                    this.check("точка привязки после сброса — 0, а не заглушка Litematica: «" + originX + "»", "0".equals(originX));
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 204 ->
                {
                    CovOut.log("[selworld] снимок 3б: курсор у карточки после «Сбросить»");
                    Screenshot.grab(mc, false);
                    // Щелчок мимо карточки — в мир
                    SupportTest.click(mc, mc.getWindow().getGuiScaledWidth() * 0.3, mc.getWindow().getGuiScaledHeight() * 0.6);
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 205 ->
                {
                    this.check("щелчок мимо карточки — снова игра с HUD", mc.gui.screen() == null && SelectionHud.active());
                    this.pushR();
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 206 ->
                {
                    this.check("R — снова курсор", mc.gui.screen() instanceof GuiSchematics && this.parked(mc));
                    this.pushR();
                    this.nextAt = Util.getMillis() + 400L;
                }
                case 207 ->
                {
                    this.check("R при курсоре — обратно в игру", mc.gui.screen() == null && SelectionHud.active());
                    this.key(mc, InputConstants.KEY_ESCAPE, 0x1B);
                    this.nextAt = Util.getMillis() + 150L;
                }
                case 14 ->
                {
                    CovOut.log("[selworld] снимок 4: Esc — экран въезжает обратно");
                    Screenshot.grab(mc, false);
                    this.check("Esc в игре вернул «Выделение»", mc.gui.screen() instanceof GuiSchematics
                            && "SELECTION".equals(String.valueOf(field(mc.gui.screen(), "tab"))));
                    this.check("меню паузы не открылось", ! (mc.gui.screen() instanceof PauseScreen));
                    this.check("HUD выключен", ! SelectionHud.active());
                    this.nextAt = Util.getMillis() + 900L;
                }
                case 15 ->
                {
                    this.check("панели доехали на места", ! this.moving(mc));
                    SupportTest.moveMouse(mc, 4, 4);
                    this.nextAt = Util.getMillis() + 200L;
                }
                case 16 ->
                {
                    CovOut.log("[selworld] снимок 5: экран вернулся");
                    Screenshot.grab(mc, false);
                    this.key(mc, InputConstants.KEY_ESCAPE, 0x1B);
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 17 ->
                {
                    this.check("Esc закрыл экран", mc.gui.screen() == null);
                    this.key(mc, InputConstants.KEY_ESCAPE, 0x1B);
                    this.nextAt = Util.getMillis() + 300L;
                }
                case 18 ->
                {
                    this.check("Esc в игре без HUD — обычная пауза", mc.gui.screen() instanceof PauseScreen);
                    mc.gui.setScreen(null);
                    Nav.clear();
                    CovOut.log("[selworld] итог: " + (this.failed ? "FAIL" : "OK"));
                    return true;
                }
                default ->
                {
                    return true;
                }
            }
        }
        catch (RuntimeException | LinkageError | ReflectiveOperationException e)
        {
            this.check("сбой: " + e, false);
            CovOut.log("[selworld] итог: FAIL");
            mc.options.keyUp.setDown(false);
            mc.gui.setScreen(null);
            return true;
        }

        return false;
    }

    /** Простое выделение перед игроком, взгляд на него — рамку видно в мире. */
    private void prepare(Minecraft mc)
    {
        SelectionManager manager = DataManager.getSelectionManager();

        if (manager.getSelectionMode() != SelectionMode.SIMPLE)
        {
            manager.switchSelectionMode();
        }

        AreaSelection selection = manager.getCurrentSelection();
        BlockPos at = mc.player.blockPosition();
        BlockPos a = at.offset(3, -2, 3);
        BlockPos b = at.offset(11, 5, 13);
        Box box = selection.getSelectedSubRegionBox();

        if (box == null)
        {
            selection.createNewSubRegionBox(a, "selworld");
            box = selection.getSelectedSubRegionBox();
        }

        selection.setSubRegionCornerPos(box, Corner.CORNER_1, a);
        selection.setSubRegionCornerPos(box, Corner.CORNER_2, b);
        mc.player.setYRot(-35.0F);
        mc.player.setXRot(25.0F);
        CovOut.log("[selworld] выделение " + a.toShortString() + " … " + b.toShortString());
    }

    private void clickEye(Minecraft mc) throws ReflectiveOperationException
    {
        Widget eye = (Widget) field(this.pane(mc), "seeWorld");
        SupportTest.click(mc, eye.x() + eye.w() / 2.0, eye.y() + eye.h() / 2.0);
    }

    private void mouse(Minecraft mc, int button, int action)
    {
        mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, 0), action);
    }

    /**
     * R — событием в очереди SDL, как от клавиатуры: хоткеи malilib 0.30 узнают зажатые клавиши только из сырого
     * события SDL, одного {@code keyPress} им мало (см. {@code KHotkey}). Обработается в ближайшем кадре.
     */
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

    /** Клавиша через KeyboardHandler — тот же путь, что у настоящей (в игре Esc идёт мимо экрана). */
    private void key(Minecraft mc, int key, int code)
    {
        KeyEvent event = new KeyEvent(key, code, 0);
        mc.keyboardHandler.keyPress(mc.getWindow().handle(), InputConstants.PRESS, event);
        mc.keyboardHandler.keyPress(mc.getWindow().handle(), InputConstants.RELEASE, event);
    }

    private int cornerX()
    {
        Box box = DataManager.getSelectionManager().getCurrentSelection().getSelectedSubRegionBox();
        return box.getPos1().getX();
    }

    private Object pane(Minecraft mc) throws ReflectiveOperationException
    {
        return field(mc.gui.screen(), "selection");
    }

    /** Кнопка шага угла 1: 0 — X, 1 — Y, 2 — Z. */
    private Widget nudge(Minecraft mc, int axis) throws ReflectiveOperationException
    {
        Object corner = field(this.pane(mc), "corner1");
        return ((Widget[]) field(corner, "plus"))[axis];
    }

    private boolean parked(Minecraft mc) throws ReflectiveOperationException
    {
        Object pane = this.pane(mc);
        Method on = pane.getClass().getDeclaredMethod("parked");
        on.setAccessible(true);
        return (boolean) on.invoke(pane);
    }

    /** Поле угла 1: 0 — X, 1 — Y, 2 — Z. */
    private Widget field(Minecraft mc, int axis) throws ReflectiveOperationException
    {
        Object corner = field(this.pane(mc), "corner1");
        return ((Widget[]) field(corner, "fields"))[axis];
    }

    private static boolean hasField(Object owner, String name)
    {
        try
        {
            owner.getClass().getDeclaredField(name);
            return true;
        }
        catch (NoSuchFieldException e)
        {
            return false;
        }
    }

    private boolean moving(Minecraft mc) throws ReflectiveOperationException
    {
        Object pane = this.pane(mc);
        Method on = pane.getClass().getDeclaredMethod("moving");
        on.setAccessible(true);
        return (boolean) on.invoke(pane);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException
    {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private void check(String what, boolean ok)
    {
        CovOut.log("[selworld] " + (ok ? "OK   " : "FAIL ") + what);
        this.failed |= ! ok;
    }

    @Override
    public void close()
    {
    }
}
