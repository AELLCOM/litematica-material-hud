package com.aell.layerlist.skin;

import com.aell.layerlist.schematics.SchematicsConfig;
import fi.dy.masa.litematica.gui.GuiMainMenu;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.GuiUtils;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Красим ли экран, который сейчас рисуется. Кадр экрана malilib открывает {@link #beginFrame} и закрывает
 * {@link #endFrame} (миксины {@code MixinGuiBaseSkin}, {@code MixinDialogSkin}); кадры вложены — окно malilib
 * сначала рисует под собой родительский экран, — поэтому стек. Кнопки, цвета, поля и подсказки спрашивают
 * {@link #skinning()}.
 *
 * <p>Под перекраской — экраны Litematica ({@code fi.dy.masa.litematica.gui.*}) и экраны malilib, открытые из них
 * (окна ввода и подтверждения, редакторы списков из настроек). Экраны других модов остаются в своём виде, даже
 * если открыты из Litematica.
 */
@Environment(EnvType.CLIENT)
public final class LitematicaSkin
{
    private static final String LITEMATICA_GUI = "fi.dy.masa.litematica.gui.";
    private static final String MALILIB_GUI = "fi.dy.masa.malilib.gui.";
    private static final int MAX_PARENTS = 8;

    /** {@code null} в стеке — кадр экрана, который не красим. */
    private static final List<@Nullable GuiBase> FRAMES = new ArrayList<>();

    private LitematicaSkin() {}

    public static boolean enabled()
    {
        // Ванильный стиль: окна Litematica остаются своими, ванильными — перекраска в цвета мода выключена
        return SchematicsConfig.LITEMATICA_SKIN.getBooleanValue()
                && ! com.aell.layerlist.shaded.ui.Style.vanilla();
    }

    public static boolean covers(@Nullable Screen screen)
    {
        if (! enabled() || screen == null || ! SkinAccess.usable())
        {
            return false;
        }

        Screen s = screen;

        for (int i = 0; s != null && i < MAX_PARENTS; ++i)
        {
            String name = s.getClass().getName();

            if (name.startsWith(LITEMATICA_GUI))
            {
                return true;
            }

            // Экран malilib наследует перекраску от того, откуда его открыли
            s = name.startsWith(MALILIB_GUI) && s instanceof GuiBase base ? base.getParent() : null;
        }

        return false;
    }

    /** Экран Litematica со своим фоном (не окно поверх родителя): фон и заголовок рисуем свои. */
    public static boolean ownsBackground(GuiBase screen)
    {
        return screen.getClass().getName().startsWith(LITEMATICA_GUI);
    }

    public static void beginFrame(GuiBase screen)
    {
        // Кадр верхнего экрана — начало отрисовки: хвост от кадра, оборванного исключением, не тянем
        if (screen == GuiUtils.getCurrentScreen())
        {
            FRAMES.clear();
        }

        GuiBase skinned = covers(screen) ? screen : null;
        FRAMES.add(skinned);

        if (skinned instanceof GuiMainMenu)
        {
            MainMenuSkin.frame(skinned);
        }
    }

    public static void endFrame()
    {
        if (! FRAMES.isEmpty())
        {
            FRAMES.removeLast();
        }
    }

    public static boolean skinning()
    {
        return ! FRAMES.isEmpty() && FRAMES.getLast() != null;
    }
}
