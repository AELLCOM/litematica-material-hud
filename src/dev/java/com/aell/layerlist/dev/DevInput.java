package com.aell.layerlist.dev;

import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * 26.2: обработчики ввода GLFW закрыты (в 26.3 они публичные — их зовёт SDL). Тесты зовут их тем же
 * порядком аргументов через этот класс; {@code onMove} без дельт, {@code textInput} — посимвольно {@code charTyped}.
 */
@Environment(EnvType.CLIENT)
final class DevInput
{
    private static Method button;
    private static Method scroll;
    private static Method move;
    private static Method key;
    private static Method typed;

    private DevInput() {}

    static void onButton(MouseHandler handler, long window, MouseButtonInfo info, int action)
    {
        call(button == null ? button = find(MouseHandler.class, "onButton", long.class, MouseButtonInfo.class, int.class) : button,
                handler, window, info, action);
    }

    static void onScroll(MouseHandler handler, long window, double x, double y)
    {
        call(scroll == null ? scroll = find(MouseHandler.class, "onScroll", long.class, double.class, double.class) : scroll,
                handler, window, x, y);
    }

    static void onMove(MouseHandler handler, long window, double x, double y, double dx, double dy)
    {
        call(move == null ? move = find(MouseHandler.class, "onMove", long.class, double.class, double.class) : move,
                handler, window, x, y);
    }

    static void keyPress(KeyboardHandler handler, long window, int action, KeyEvent event)
    {
        call(key == null ? key = find(KeyboardHandler.class, "keyPress", long.class, int.class, KeyEvent.class) : key,
                handler, window, action, event);
    }

    static void charTyped(KeyboardHandler handler, long window, CharacterEvent event)
    {
        call(typed == null ? typed = find(KeyboardHandler.class, "charTyped", long.class, CharacterEvent.class) : typed,
                handler, window, event);
    }

    static void textInput(KeyboardHandler handler, long window, String text)
    {
        text.codePoints().forEach(c -> charTyped(handler, window, new CharacterEvent(c)));
    }

    private static Method find(Class<?> owner, String name, Class<?>... types)
    {
        try
        {
            Method method = owner.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException(owner.getSimpleName() + "." + name, e);
        }
    }

    private static void call(Method method, Object target, Object... args)
    {
        try
        {
            method.invoke(target, args);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(method.getName(), e);
        }
    }
}
