package com.aell.layerlist.dev;

import com.aell.layerlist.schematics.gui.materials.MaterialLine;
import com.aell.layerlist.schematics.gui.materials.MaterialsJob;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.shaded.ui.Strings;
import com.aell.layerlist.ui.kit.Button;
import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.malilib.gui.GuiBase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.jetbrains.annotations.Nullable;

/**
 * Доступ харнесса «k» к внутренностям экрана «Схемы» (пакет {@code schematics.gui} закрыт:
 * панели и их методы package-private). Только чтение состояния и поиск виджетов — сами действия
 * идут обычным вводом экрана (клавиши, клики), чтобы проверялся путь игрока.
 *
 * <p>Поля ищутся по типу (имя класса панели, {@link Button} по подписи из ключа перевода), а не
 * по имени: переименование поля не ломает харнесс, пропажа — даёт понятную строку FAIL.
 */
@Environment(EnvType.CLIENT)
final class KProbe
{
    static final String GUI_PACKAGE = "com.aell.layerlist.schematics.gui.";

    private KProbe() {}

    /** Первое поле экземпляра, чей тип называется {@code simpleName} (для закрытых классов панелей). */
    static @Nullable Object fieldNamedType(@Nullable Object owner, String simpleName)
    {
        if (owner == null)
        {
            return null;
        }

        for (Field field : fields(owner.getClass()))
        {
            if (field.getType().getSimpleName().equals(simpleName))
            {
                return read(field, owner);
            }
        }

        return null;
    }

    /** Первое поле экземпляра, приводимое к {@code type}. */
    static <T> @Nullable T fieldOfType(@Nullable Object owner, Class<T> type)
    {
        List<T> all = fieldsOfType(owner, type);
        return all.isEmpty() ? null : all.getFirst();
    }

    static <T> List<T> fieldsOfType(@Nullable Object owner, Class<T> type)
    {
        List<T> out = new ArrayList<>();

        if (owner == null)
        {
            return out;
        }

        for (Field field : fields(owner.getClass()))
        {
            if (type.isAssignableFrom(field.getType()))
            {
                Object value = read(field, owner);

                if (value != null)
                {
                    out.add(type.cast(value));
                }
            }
        }

        return out;
    }

    /** Кнопка набора виджетов, чья подпись — перевод ключа (как её строит экран). */
    static @Nullable Button button(@Nullable Object owner, String langKey)
    {
        String label = Strings.plain(langKey);

        for (Button button : fieldsOfType(owner, Button.class))
        {
            if (label.equals(button.label()))
            {
                return button;
            }
        }

        return null;
    }

    /**
     * Кнопка экспорта карточки «Библиотеки»: с 0.0.27 — «Экспорт списка материалов ▾», а где не влезает —
     * «Экспорт материалов ▾»; ищется по любой из двух подписей.
     */
    static @Nullable Button cardExport(@Nullable Object infoPane)
    {
        Button button = button(infoPane, "layerlist.schematics.info.export");

        return button != null ? button : button(infoPane, "layerlist.schematics.info.export.short");
    }

    /** Вызов метода экземпляра по имени и числу аргументов (видимость не важна). */
    static @Nullable Object call(Object target, String name, Object... args) throws ReflectiveOperationException
    {
        Method method = method(target.getClass(), name, args);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    static boolean hasMethod(Object target, String name, int arity)
    {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Method method : c.getDeclaredMethods())
            {
                if (method.getName().equals(name) && method.getParameterCount() == arity)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /** Константа закрытого перечисления ({@code GuiSchematics$Tab}) по имени. */
    static Object enumConstant(String className, String constant) throws ReflectiveOperationException
    {
        Class<?> type = Class.forName(className);

        for (Object value : type.getEnumConstants())
        {
            if (((Enum<?>) value).name().equals(constant))
            {
                return value;
            }
        }

        throw new NoSuchFieldException(className + "." + constant);
    }

    /** Компонент записи по типу (строка списка файлов — закрытая запись с {@code SchematicEntry}). */
    static @Nullable Object recordComponent(@Nullable Object record, Class<?> type)
    {
        if (record == null || ! record.getClass().isRecord())
        {
            return null;
        }

        for (RecordComponent component : record.getClass().getRecordComponents())
        {
            if (type.isAssignableFrom(component.getType()))
            {
                try
                {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(record);
                }
                catch (ReflectiveOperationException e)
                {
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Сколько строк материалов держит панель — без знания её устройства (интерфейс «Материалов»
     * пишется параллельно): коллекции {@link MaterialRow} в её полях и в полях её виджетов набора
     * ({@code ui.kit}, например таблица). −1 — таких коллекций нет (заглушка); −2 — какое-то
     * поле-будущее или {@link MaterialsJob} ещё не завершено (строки считаются). Строки таблицы
     * волны 2 — {@link MaterialLine} (обёртка {@link MaterialRow}), считаются так же.
     */
    static int materialRows(@Nullable Object pane)
    {
        if (pane == null)
        {
            return -1;
        }

        int rows = -1;

        for (Field field : fields(pane.getClass()))
        {
            Object value = read(field, pane);

            if (value instanceof CompletableFuture<?> future && ! future.isDone()
                    || value instanceof MaterialsJob job && job.running())
            {
                return -2;
            }

            rows = Math.max(rows, rowsIn(value));

            if (value != null && value.getClass().getName().startsWith("com.aell.layerlist.ui.kit."))
            {
                for (Field inner : fields(value.getClass()))
                {
                    rows = Math.max(rows, rowsIn(read(inner, value)));
                }
            }
        }

        return rows;
    }

    private static int rowsIn(@Nullable Object value)
    {
        if (value instanceof Collection<?> collection && ! collection.isEmpty()
                && (collection.iterator().next() instanceof MaterialRow
                    || collection.iterator().next() instanceof MaterialLine))
        {
            return collection.size();
        }

        return -1;
    }

    // ===== Ввод =====

    static MouseButtonEvent left(double x, double y)
    {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    static KeyEvent key(int key)
    {
        return new KeyEvent(key, 0, 0);
    }

    static KeyEvent key(int key, int modifiers)
    {
        return new KeyEvent(key, 0, modifiers);
    }

    /** Клавиша экрану malilib — как её отдал бы {@code keyPressed}. */
    static boolean press(GuiBase screen, int key)
    {
        return screen.onKeyTyped(key(key));
    }

    static boolean press(GuiBase screen, int key, int modifiers)
    {
        return screen.onKeyTyped(key(key, modifiers));
    }

    /** Клик левой кнопкой экрану: нажатие и отпускание в одной точке. */
    static boolean click(GuiBase screen, double x, double y)
    {
        boolean handled = screen.onMouseClicked(left(x, y), false);
        screen.onMouseReleased(left(x, y));
        return handled;
    }

    /** Нажать кнопку набора виджетов в её центре, минуя раскладку экрана (кнопка за краем прокрутки). */
    static boolean pressDirect(Button button)
    {
        double x = button.x() + button.w() / 2.0;
        double y = button.y() + button.h() / 2.0;
        boolean handled = button.mouseClicked(left(x, y), false);
        button.mouseReleased(left(x, y));
        return handled;
    }

    // ===== Внутреннее =====

    private static List<Field> fields(Class<?> type)
    {
        List<Field> out = new ArrayList<>();

        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
        {
            for (Field field : c.getDeclaredFields())
            {
                if (! Modifier.isStatic(field.getModifiers()))
                {
                    out.add(field);
                }
            }
        }

        return out;
    }

    private static @Nullable Object read(Field field, Object owner)
    {
        try
        {
            field.setAccessible(true);
            return field.get(owner);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Object[] args) throws NoSuchMethodException
    {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
        {
            search:
            for (Method method : c.getDeclaredMethods())
            {
                if (! method.getName().equals(name) || method.getParameterCount() != args.length)
                {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();

                for (int i = 0; i < params.length; ++i)
                {
                    if (args[i] != null && ! box(params[i]).isInstance(args[i]))
                    {
                        continue search;
                    }
                }

                return method;
            }
        }

        throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    }

    private static Class<?> box(Class<?> type)
    {
        if (! type.isPrimitive())
        {
            return type;
        }

        return type == int.class ? Integer.class : type == boolean.class ? Boolean.class : type == long.class ? Long.class
                : type == double.class ? Double.class : type == float.class ? Float.class : Object.class;
    }
}
