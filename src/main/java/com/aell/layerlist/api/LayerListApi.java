package com.aell.layerlist.api;

import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.storage.StorageSearch;
import com.aell.layerlist.util.Chat;
import com.aell.layerlist.verify.LayerVerifier;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class LayerListApi
{
    /** 6 — превью участка из нескольких размещений, срез и вид сбоку в 3D, проекция точек, мир Voxy под голограммой. */
    public static final int API_VERSION = 6;

    /** Ключ вкладки Zone Focus — по нему сосед открывает экран сразу на ней. */
    public static final String TAB_ZONE_FOCUS = "layerlist.gui.tab_zone_focus";

    public record AddonScreen(String labelKey, Supplier<Screen> factory) {}

    public sealed interface AddonOption permits AddonToggle, AddonSlider
    {
        String configKey();

        boolean child();

        @Nullable Supplier<String> gate();
    }

    public record AddonToggle(String configKey, BooleanSupplier getter, Consumer<Boolean> setter,
            boolean defaultValue, boolean child, @Nullable Supplier<String> gate)
            implements AddonOption {}

    public record AddonSlider(String configKey, DoubleSupplier getter, DoubleConsumer setter,
            double min, double max, double step, double defaultValue, String format,
            boolean child, @Nullable Supplier<String> gate)
            implements AddonOption {}

    public record AddonSection(String titleKey, List<AddonOption> options) {}

    public record AddonTab(String labelKey, @Nullable String iconNamespace, @Nullable String iconPath,
            List<AddonSection> sections, @Nullable Runnable onSave) {}

    private static final List<AddonScreen> ADDON_SCREENS = new ArrayList<>();

    private static final List<AddonTab> ADDON_TABS = new ArrayList<>();

    @Nullable
    private static CountScope scope;

    private LayerListApi() {}

    public static void setCountScope(@Nullable CountScope newScope)
    {
        scope = newScope;
        LayerCountManager.getInstance().markDirty();
    }

    @Nullable
    public static CountScope getCountScope()
    {
        return scope;
    }

    public static boolean isScopeActive()
    {
        return scope != null && scope.isActive();
    }

    public static boolean hasData()
    {
        return LayerCountManager.getInstance().hasData();
    }

    public static List<MaterialListEntry> getEntries()
    {
        return LayerCountManager.getInstance().getAllEntries();
    }

    public static long getBlocksTotal()
    {
        return LayerCountManager.getInstance().getBlocksTotal();
    }

    public static long getBlocksMissing()
    {
        return LayerCountManager.getInstance().getBlocksMissing();
    }

    public static void markDirty()
    {
        LayerCountManager.getInstance().markDirty();
    }

    public static int toggleStorageSearch(List<MaterialListEntry> entries)
    {
        return StorageSearch.getInstance().togglePage(entries);
    }

    public static void clearStorageSearch()
    {
        StorageSearch.getInstance().clear();
    }

    public static boolean isStorageSearchActive()
    {
        return StorageSearch.getInstance().isActive();
    }

    public static int countSearchable(List<MaterialListEntry> entries)
    {
        return StorageSearch.countSearchable(entries);
    }

    /**
     * Проверка блоков — то же, что Ctrl+B: Zone Focus отдаёт сюда свою клавишу и кнопку, чтобы
     * проверка в мире была одна. Ответ уже ушёл в чат. API 5.
     */
    public static Component toggleVerification()
    {
        Component message = LayerVerifier.getInstance().toggle();
        Chat.send(message);
        return message;
    }

    /** 3D-превью схемы с зонами (изометрия библиотеки) — для «Управления зонами» Zone Focus. API 5. */
    public static ZonePreview createZonePreview()
    {
        return new ZonePreview();
    }

    /** Качество 3D-превью (библиотека и 3D Zone Focus): истина — «Качество», ложь — «Оптимизация». API 5. */
    public static boolean isPreviewQuality()
    {
        return com.aell.layerlist.schematics.SchematicsConfig.previewQuality()
                == com.aell.layerlist.schematics.SchematicsConfig.PreviewQuality.QUALITY;
    }

    /** Сменить качество 3D-превью и сохранить настройки. API 5. */
    public static void setPreviewQuality(boolean quality)
    {
        com.aell.layerlist.schematics.SchematicsConfig.PREVIEW_QUALITY.setOptionListValue(quality
                ? com.aell.layerlist.schematics.SchematicsConfig.PreviewQuality.QUALITY
                : com.aell.layerlist.schematics.SchematicsConfig.PreviewQuality.OPTIMIZED);
        new com.aell.layerlist.config.LayerListConfigs().save();
    }

    /**
     * Ванильный стиль интерфейса Material HUD (настройка «Ванильный стиль»). Соседи — Zone Focus, EasyPlaceFix+ —
     * берут его в свой стиль, чтобы их окна рядом с Material HUD были в той же теме (22.10.2026). Ищут
     * отражением: у старого Material HUD метода нет — тогда их собственная настройка.
     */
    public static boolean isVanillaStyle()
    {
        return com.aell.layerlist.config.LayerListConfigs.UI_STYLE.getIntegerValue() >= 1;
    }

    /** Оформление Material HUD: 0 — Aell UI, 1 — Vanilla, 2 — Vanilla+ (22.10.2026); соседи берут его отражением. */
    public static int uiStyle()
    {
        return com.aell.layerlist.config.LayerListConfigs.UI_STYLE.getIntegerValue();
    }

    public static boolean isVerificationActive()
    {
        return LayerVerifier.getInstance().isHighlighting();
    }

    public static String getVerificationSummary()
    {
        return LayerVerifier.getInstance().getSummary();
    }

    public static void registerConfigButton(String labelKey, Supplier<Screen> factory)
    {
        ADDON_SCREENS.add(new AddonScreen(labelKey, factory));
    }

    public static List<AddonScreen> getConfigButtons()
    {
        return List.copyOf(ADDON_SCREENS);
    }

    /**
     * Экран настроек Material HUD, открытый на нужной вкладке. Нужен соседям, у которых свой
     * экран уступил место вкладке здесь: жать «Настройки» в Mod Menu или на экранах Litematica
     * человек должен там же, где эти настройки теперь и живут. {@code tabKey} неизвестен —
     * экран откроется на запомненной вкладке.
     */
    public static Screen createSettings(@Nullable Screen parent, @Nullable String backLabelKey,
            @Nullable String tabKey)
    {
        com.aell.layerlist.config.GuiConfigs screen =
                com.aell.layerlist.config.GuiConfigs.create(parent, backLabelKey);

        if (tabKey != null)
        {
            screen.showTab(tabKey);
        }

        return screen;
    }

    public static void registerConfigTab(AddonTab tab)
    {
        ADDON_TABS.add(tab);
    }

    public static List<AddonTab> getConfigTabs()
    {
        return List.copyOf(ADDON_TABS);
    }
}
