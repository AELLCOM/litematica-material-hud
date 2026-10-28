package com.aell.ui;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.config.IConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

/**
 * Общие опции двух наших модов с одинаковым функционалом: модуль Easy Place внутри
 * Litematica Material HUD и отдельный Litematica EasyPlaceFixPlus. Движок в каждый момент ведёт
 * один из них, но настраиваться человек должен из любого — и видеть там одни и те же значения,
 * а не два разошедшихся набора.
 *
 * <p>Собрать моды друг против друга нельзя: они ставятся по отдельности. Поэтому связь —
 * рефлексия по одному классу-мосту с фиксированным именем ({@code option(String)} отдаёт объект
 * опции по имени, {@code saveOwn()} пишет свой файл). Fabric держит все моды в одном
 * загрузчике, значит {@code ConfigBoolean} у соседа — тот же самый класс malilib, и его объект
 * можно отдать прямо в {@link Values}: экран правит живое значение соседа, без копий и
 * пересылок. Соседа нет — мост не находится, и всё работает на своих объектах.
 *
 * <p>Имена опций в обоих модах совпадают дословно, это и есть ключ сопоставления. Список
 * общих опций каждый мод задаёт сам и отдаёт через мост только его — так настройка, которая
 * лишь называется одинаково (например «сообщения в чат»), никогда не переедет к соседу.
 */
@Environment(EnvType.CLIENT)
public final class SharedOptions
{
    private final String bridgeClass;

    private final Map<String, Method> actions = new HashMap<>();

    private boolean resolved;
    private @Nullable Class<?> bridge;
    private @Nullable Method optionMethod;
    private @Nullable Method saveMethod;

    private SharedOptions(String bridgeClass)
    {
        this.bridgeClass = bridgeClass;
    }

    public static SharedOptions of(String bridgeClass)
    {
        return new SharedOptions(bridgeClass);
    }

    /** Стоит ли сосед: мост нашёлся и отвечает. */
    public boolean present()
    {
        this.resolve();

        return this.optionMethod != null;
    }

    /**
     * Объект опции, который надо показывать в экране: у соседа, если он есть, иначе свой.
     * Тип сверяется на месте — если сосед отдал что-то другое, остаёмся на своём.
     */
    public ConfigBoolean bool(ConfigBoolean own)
    {
        return this.option(own.getName()) instanceof ConfigBoolean shared ? shared : own;
    }

    public IConfigInteger integer(ConfigInteger own)
    {
        return this.option(own.getName()) instanceof IConfigInteger shared ? shared : own;
    }

    public @Nullable IConfigBase option(String name)
    {
        this.resolve();

        if (this.optionMethod == null)
        {
            return null;
        }

        try
        {
            return (IConfigBase) this.optionMethod.invoke(null, name);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    /** Значения соседа → в свои объекты: свой файл на диске не должен отставать. */
    public void pull(List<? extends IConfigBase> mine)
    {
        this.copy(mine, true);
    }

    /** Свои значения → в объекты соседа: чтобы его файл не отставал от нашего. */
    public void push(List<? extends IConfigBase> mine)
    {
        this.copy(mine, false);
    }

    /**
     * Действие у соседа: статический метод моста без аргументов. Нужно там, где кнопка живёт
     * в нашем экране, а движок ведёт сосед: свои объекты в это время спят, жать надо его.
     * Вернёт false, если моста или метода нет — вызывающий покажет строку неактивной.
     */
    public boolean call(String method)
    {
        Method action = this.action(method);

        if (action == null)
        {
            return false;
        }

        try
        {
            action.invoke(null);

            return true;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Признак у соседа: статический boolean-метод моста без аргументов; нет моста — false. */
    public boolean flag(String method)
    {
        Method action = this.action(method);

        if (action == null)
        {
            return false;
        }

        try
        {
            return action.invoke(null) instanceof Boolean value && value;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    /** Есть ли у соседа такое действие: сосед прошлой сборки его ещё не знает. */
    public boolean has(String method)
    {
        return this.action(method) != null;
    }

    private @Nullable Method action(String name)
    {
        if (this.actions.containsKey(name))
        {
            return this.actions.get(name);
        }

        this.resolve();

        Method found = null;

        if (this.bridge != null)
        {
            try
            {
                found = this.bridge.getMethod(name);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                found = null;
            }
        }

        this.actions.put(name, found);

        return found;
    }

    /**
     * Просим соседа записать свой файл. Мост только пишет и ничего не зовёт обратно —
     * иначе два сохранения ушли бы в рекурсию.
     */
    public void saveSibling()
    {
        this.resolve();

        if (this.saveMethod == null)
        {
            return;
        }

        try
        {
            this.saveMethod.invoke(null);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            // Сосед не записался — свои значения уже сохранены, при следующем заходе догонит
        }
    }

    private void copy(List<? extends IConfigBase> mine, boolean fromSibling)
    {
        for (IConfigBase own : mine)
        {
            IConfigBase other = this.option(own.getName());

            if (other == null || other == own)
            {
                continue;
            }

            IConfigBase from = fromSibling ? other : own;
            IConfigBase to = fromSibling ? own : other;

            if (from instanceof IConfigBoolean src && to instanceof IConfigBoolean dst)
            {
                dst.setBooleanValue(src.getBooleanValue());
            }
            else if (from instanceof IConfigInteger src && to instanceof IConfigInteger dst)
            {
                dst.setIntegerValue(src.getIntegerValue());
            }
        }
    }

    private void resolve()
    {
        if (this.resolved)
        {
            return;
        }

        this.resolved = true;

        try
        {
            this.bridge = Class.forName(this.bridgeClass, true,
                    SharedOptions.class.getClassLoader());

            this.optionMethod = this.bridge.getMethod("option", String.class);
            this.saveMethod = this.bridge.getMethod("saveOwn");
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            this.bridge = null;
            this.optionMethod = null;
            this.saveMethod = null;
        }
    }
}
