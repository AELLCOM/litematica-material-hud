package com.aell.ui;

import fi.dy.masa.litematica.gui.GuiConfigs;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IConfigGuiAllTab;
import fi.dy.masa.malilib.util.StringUtils;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class LitematicaSlots
{
    public static final int TAB_ROW_Y = 26;
    public static final int TAB_H = 20;
    public static final int TAB_GAP = 2;
    public static final int SCREEN_MARGIN = 10;
    public static final int RESERVED_W = 78;
    public static final int ICON_SIZE = 16;
    public static final int ICON_PAD = 3;
    public static final int ICON_GAP = 16;
    public static final int COMPACT_W = TAB_H;

    private static final Set<String> ICONLESS = Set.of();

    // Кэш поиска useAllTab(): экранов наперечёт, а initGui зовётся на каждое открытие и ресайз.
    private static Class<?> cachedOwner;
    private static Method cachedMethod;

    private LitematicaSlots() {}

    public static int autoWidth(String label)
    {
        return new ButtonGeneric(0, 0, -1, TAB_H, label).getWidth();
    }

    public static List<String> configTabLabels(boolean withAllTab)
    {
        List<String> labels = new ArrayList<>();

        for (GuiConfigs.ConfigGuiTab tab : GuiConfigs.ConfigGuiTab.values())
        {
            if (! withAllTab && tab == GuiConfigs.ConfigGuiTab.ALL)
            {
                continue;
            }

            labels.add(tab.getDisplayName());
        }

        return labels;
    }

    public static boolean hasAllTab(Object screen)
    {
        if (screen instanceof IConfigGuiAllTab tabbed)
        {
            return tabbed.useAllTab();
        }

        // Экран слоёв (GuiRenderLayer) интерфейс не реализует: до Litematica 0.28.6 вкладки All
        // у него не было вовсе, а с 0.28.6 ряд собирается циклом по ConfigGuiTab.values(), и
        // включение All решает приватный useAllTab(). Спрашиваем его отражением: иначе на новых
        // версиях ряд считается на вкладку короче и наши кнопки наезжают на All, а на старых —
        // на вкладку длиннее, и ряд зря уходит в compact или на вторую строку.
        Boolean own = useAllTab(screen);

        return own != null && own;
    }

    @Nullable
    private static Boolean useAllTab(Object screen)
    {
        Class<?> owner = screen.getClass();

        if (owner != cachedOwner)
        {
            cachedOwner = owner;
            cachedMethod = findUseAllTab(owner);
        }

        Method method = cachedMethod;

        if (method == null)
        {
            return null;
        }

        try
        {
            return method.invoke(screen) instanceof Boolean flag ? flag : null;
        }
        catch (ReflectiveOperationException | LinkageError | RuntimeException e)
        {
            return null;
        }
    }

    @Nullable
    private static Method findUseAllTab(Class<?> owner)
    {
        for (Class<?> cls = owner; cls != null && cls != Object.class; cls = cls.getSuperclass())
        {
            try
            {
                Method method = cls.getDeclaredMethod("useAllTab");

                if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)
                {
                    method.setAccessible(true);

                    return method;
                }
            }
            catch (NoSuchMethodException | LinkageError | RuntimeException ignored)
            {
            }
        }

        return null;
    }

    public static ButtonGeneric addonTabButton(GuiBase screen, String label, boolean withAllTab,
            String[] order, String selfModId)
    {
        return addonTabButton(screen, label, null, withAllTab, order, selfModId);
    }

    public static ButtonGeneric addonTabButton(GuiBase screen, String label, @Nullable Identifier icon,
            boolean withAllTab, String[] order, String selfModId)
    {
        int rowEnd = tabRowEnd(configTabLabels(withAllTab));
        List<String> present = presentAddons(order, selfModId);
        int index = Math.max(0, present.indexOf(selfModId));
        int limit = screen.width - SCREEN_MARGIN;
        boolean compact = rowEnd + slotSpan(present, 0, present.size(), false) > limit;
        int width = compact && icon != null
                ? COMPACT_W
                : autoWidth(label) + (icon == null ? 0 : ICON_SIZE + ICON_GAP);
        int before = slotSpan(present, 0, index, compact);
        int after = slotSpan(present, index + 1, present.size(), compact);
        boolean sameRow = rowEnd + before + width + after <= limit;
        int x = Math.max(SCREEN_MARGIN,
                Math.min((sameRow ? rowEnd : SCREEN_MARGIN) + before, limit - width - after));
        int y = sameRow ? TAB_ROW_Y : TAB_ROW_Y + TAB_H + TAB_GAP;

        if (icon == null)
        {
            return new ButtonGeneric(x, y, width, TAB_H, label);
        }

        return compact
                ? new IconButton(x, y, width, TAB_H, "", icon, label)
                : new IconButton(x, y, width, TAB_H, label, icon);
    }

    private static final class IconButton extends ButtonGeneric
    {
        private final Identifier icon;
        private final boolean centered;

        private IconButton(int x, int y, int width, int height, String label, Identifier icon)
        {
            super(x, y, width, height, label);
            this.icon = icon;
            this.centered = false;
        }

        private IconButton(int x, int y, int width, int height, String label, Identifier icon,
                String hoverText)
        {
            super(x, y, width, height, label, hoverText);
            this.icon = icon;
            this.centered = true;
        }

        @Override
        public void render(fi.dy.masa.malilib.render.GuiContext ctx, int mouseX, int mouseY, boolean selected)
        {
            super.render(ctx, mouseX, mouseY, selected);

            Paint.texture(ctx, this.icon,
                    this.x + (this.centered ? (this.width - ICON_SIZE) / 2 : ICON_PAD),
                    this.y + (this.height - ICON_SIZE) / 2, ICON_SIZE);
        }
    }

    public static int tabRowEnd(List<String> nativeLabels)
    {
        int x = SCREEN_MARGIN;

        for (String label : nativeLabels)
        {
            x += autoWidth(label) + TAB_GAP;
        }

        return x;
    }

    public static int slotWidth(String modId, boolean compact)
    {
        boolean iconless = ICONLESS.contains(modId);

        if (compact && ! iconless)
        {
            return COMPACT_W;
        }

        String label = StringUtils.getTranslatedOrFallback(modId + ".gui.litematica_button", null);

        if (label == null)
        {
            return RESERVED_W;
        }

        return autoWidth(label) + (iconless ? 0 : ICON_SIZE + ICON_GAP);
    }

    public static List<String> presentAddons(String[] order, String selfModId)
    {
        List<String> present = new ArrayList<>();

        for (String modId : order)
        {
            if (modId.equals(selfModId) || FabricLoader.getInstance().isModLoaded(modId))
            {
                present.add(modId);
            }
        }

        // Сам мод — всегда в ряду, даже когда порядок пришёл из чужого мода старой сборки,
        // который этого мода ещё не знает: Mixin вливает одноимённые static-поля всех
        // аддонов в один GuiConfigs, и чей массив переживёт слияние — лотерея. Без этого
        // indexOf давал -1 → слот 0, и кнопка вставала под кнопку соседа.
        if (! present.contains(selfModId))
        {
            present.add(selfModId);
        }

        return present;
    }

    public static int slotSpan(List<String> present, int from, int to, boolean compact)
    {
        int span = 0;

        for (int i = from; i < to; ++i)
        {
            span += slotWidth(present.get(i), compact) + TAB_GAP;
        }

        return span;
    }
}
