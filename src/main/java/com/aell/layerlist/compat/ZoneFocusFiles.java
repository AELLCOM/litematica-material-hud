package com.aell.layerlist.compat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Зоны Zone Focus для схемы-файла — библиотеке: пункты меню «Разбить на зоны…» / «Управление зонами…»,
 * зоны на превью, разбиение без размещения. Мост — {@code com.aell.zonefocus.compat.ZoneFocusBridge}
 * (Zone Focus 0.0.8+), методы находим по имени: нет Zone Focus или он старше — {@link #available()} ложь,
 * и библиотека живёт как раньше. Сбой вызова не роняет экран — пишется в лог один раз.
 */
@Environment(EnvType.CLIENT)
public final class ZoneFocusFiles
{
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static final String BRIDGE = "com.aell.zonefocus.compat.ZoneFocusBridge";
    private static final Map<String, Method> METHODS = new HashMap<>();

    private static boolean probed;
    private static @Nullable Class<?> bridge;
    private static boolean warned;

    private ZoneFocusFiles() {}

    private static @Nullable Class<?> bridge()
    {
        if (! probed)
        {
            probed = true;

            try
            {
                Class<?> c = Class.forName(BRIDGE);
                c.getMethod("fileZoneBoxes", String.class, int.class, int.class, int.class);
                bridge = c;
            }
            catch (ReflectiveOperationException | LinkageError e)
            {
                bridge = null;
            }
        }

        return bridge;
    }

    /** Стоит Zone Focus с зонами для файлов. */
    public static boolean available()
    {
        return bridge() != null;
    }

    private static @Nullable Object call(String name, Class<?>[] types, Object... args)
    {
        Class<?> c = bridge();

        if (c == null)
        {
            return null;
        }

        try
        {
            Method m = METHODS.get(name);

            if (m == null)
            {
                m = c.getMethod(name, types);
                METHODS.put(name, m);
            }

            return m.invoke(null, args);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            if (! warned)
            {
                warned = true;
                LOGGER.warn("[layerlist] Zone Focus: вызов {} не прошёл", name, e);
            }

            return null;
        }
    }

    private static String key(Path file)
    {
        return file.toAbsolutePath().normalize().toString();
    }

    /** Сетка файла {nx, ny, nz} в осях схемы; {@code null} — не разбит сеткой. */
    public static int @Nullable [] grid(Path file)
    {
        return call("fileZoneGrid", new Class<?>[] { String.class }, key(file)) instanceof int[] g ? g : null;
    }

    /** Зоны файла в блоках схемы от минимального угла регионов, по номерам; {@code null} — зон нет. */
    public static int @Nullable [][] boxes(Path file, int sizeX, int sizeY, int sizeZ)
    {
        return call("fileZoneBoxes", new Class<?>[] { String.class, int.class, int.class, int.class },
                key(file), sizeX, sizeY, sizeZ) instanceof int[][] b ? b : null;
    }

    /** Разбит ли файл (сеткой или зонами своего размещения). */
    public static boolean hasZones(Path file)
    {
        int[][] b = boxes(file, 1, 1, 1);
        return b != null && b.length > 0;
    }

    public static int active(Path file)
    {
        return call("fileActiveZone", new Class<?>[] { String.class }, key(file)) instanceof Integer i ? i : -1;
    }

    /**
     * Готовность каждой зоны в процентах (−1 — ещё не посчитана), если зоны файла стоят на его размещении;
     * иначе {@code null}. Скана ещё не было — Zone Focus запускает его сам.
     */
    public static int @Nullable [] progress(Path file)
    {
        return call("fileZoneProgress", new Class<?>[] { String.class }, key(file)) instanceof int[] p ? p : null;
    }

    public static void setActive(Path file, int index)
    {
        call("setFileActiveZone", new Class<?>[] { String.class, int.class }, key(file), index);
    }

    public static void split(Path file, int nx, int ny, int nz)
    {
        call("setFileZoneGrid", new Class<?>[] { String.class, int.class, int.class, int.class }, key(file), nx, ny, nz);
    }

    public static void clear(Path file)
    {
        call("clearFileZones", new Class<?>[] { String.class }, key(file));
    }

    public static boolean placed(Path file)
    {
        return Boolean.TRUE.equals(call("filePlaced", new Class<?>[] { String.class }, key(file)));
    }

    // ===== Текущие зоны Zone Focus — для карточки «Управление зонами» во вкладке =====

    /** Имя схемы, с которой работает «Управление зонами»; {@code null} — включённой схемы нет. */
    public static @Nullable String placementName()
    {
        return call("zonePlacementName", new Class<?>[0]) instanceof String s ? s : null;
    }

    /** {число зон, активная, nx, ny, nz} (сетка нулями, если не сеткой); {@code null} — зон нет. */
    public static int @Nullable [] summary()
    {
        return call("zoneSummary", new Class<?>[0]) instanceof int[] a ? a : null;
    }

    /** Зоны в мире по номерам; {@code null} — зон нет. */
    public static int @Nullable [][] worldBoxes()
    {
        return call("zoneBoxes", new Class<?>[0]) instanceof int[][] b ? b : null;
    }

    /** Готовность зон в процентах (−1 — не посчитана); {@code null} — зон нет. */
    public static int @Nullable [] zoneProgress()
    {
        return call("zoneProgress", new Class<?>[0]) instanceof int[] p ? p : null;
    }

    public static void setActiveZone(int index)
    {
        call("setActiveZone", new Class<?>[] { int.class }, index);
    }

    /** «Управление зонами» Zone Focus для размещения файла; не размещён — {@code false}. */
    public static boolean openManager(Path file)
    {
        return Boolean.TRUE.equals(call("openManagerForFile", new Class<?>[] { String.class }, key(file)));
    }
}
