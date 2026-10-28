package com.aell.layerlist.storage;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.schematics.materials.MaterialRow;
import com.aell.layerlist.schematics.materials.SchematicMaterials;
import com.aell.layerlist.util.Chat;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * «Подсветить все сундуки» (вкладка «Поиск», 17.10.2026): пока опция включена, поиск по хранилищам берёт не страницу
 * панели и не слой, а все материалы выбранного размещения целиком — и отмечает каждый сундук, бочку и шалкер, где лежит
 * хоть что-то из них. С 1.0.14 (18.10.2026, просьба Arkadiusz) — как поиск страницы: у каждого предмета свой цвет,
 * над сундуком подписаны те нужные предметы, что в нём лежат, и полоска строки панели того же цвета. Тяжело (много
 * отметок, весь список схемы), поэтому по умолчанию выключено и включается только после предупреждения. Сменили
 * выбранное размещение — список пересчитывается. Выключили — поиск переходит на страницу панели (переключатель «весь
 * список / только панель»).
 */
@Environment(EnvType.CLIENT)
public final class AllChestsSearch
{
    private static boolean wasOn;
    private static @Nullable LitematicaSchematic countedFor;
    private static @Nullable CompletableFuture<List<MaterialRow>> pending;

    private AllChestsSearch() {}

    public static boolean enabled()
    {
        return LayerListConfigs.SEARCH_ALL_CHESTS.getBooleanValue();
    }

    /**
     * Спросить и включить: окно с предупреждением поверх текущего экрана, «Включить» — {@code enable}, потом назад.
     * Выключение — без вопроса (его делает вызывающий).
     */
    public static void askToEnable(Runnable enable)
    {
        Minecraft mc = Minecraft.getInstance();
        Screen back = mc.screen;
        mc.setScreen(new ConfirmScreen(yes ->
        {
            if (yes)
            {
                enable.run();
            }

            mc.setScreen(back);
        }, Component.translatable("layerlist.message.search_all.warn.title"),
                Component.translatable("layerlist.message.search_all.warn.text"),
                Component.translatable("layerlist.message.search_all.warn.yes"),
                Component.translatable("layerlist.message.search_all.warn.no")));
    }

    /** Пересчитать заново на следующем тике (сменились цвета подсветки). */
    public static void recount()
    {
        countedFor = null;
        pending = null;
    }

    /**
     * Клавиша — переключатель «все сундуки / только список панели» (18.10.2026): без окна-предупреждения, оно только у
     * опции в настройках; какой режим включился — {@link Chat#notice}. По умолчанию клавиша не назначена.
     */
    public static void toggleFromHotkey()
    {
        boolean on = ! enabled();
        LayerListConfigs.SEARCH_ALL_CHESTS.setBooleanValue(on);
        Chat.notice(Chat.tr(on ? "layerlist.message.search_all.mode_all" : "layerlist.message.search_all.mode_page",
                Chat.TEXT));
    }

    /** Тик клиента: включили — посчитать материалы размещения и отдать поиску; выключили — снять отметки. */
    public static void tick(Minecraft mc)
    {
        boolean on = enabled() && mc.level != null && mc.player != null;

        if (! on)
        {
            if (wasOn)
            {
                wasOn = false;
                countedFor = null;
                pending = null;
                StorageSearch.getInstance().backToPage(mc.level != null
                        ? LayerCountManager.getInstance().searchEntries() : List.of());
            }

            return;
        }

        wasOn = true;
        LitematicaSchematic schematic = currentSchematic();

        if (schematic == null)
        {
            if (countedFor != null)
            {
                countedFor = null;
                StorageSearch.getInstance().clear();
            }

            return;
        }

        if (schematic != countedFor && pending == null)
        {
            countedFor = schematic;
            CompletableFuture<List<MaterialRow>> job = SchematicMaterials.ofLitematica(schematic);
            pending = job;
            job.whenComplete((rows, error) ->
            {
                if (pending != job)
                {
                    return;
                }

                pending = null;

                if (error == null && rows != null && enabled())
                {
                    List<ItemStack> stacks = new ArrayList<>();

                    for (MaterialRow row : rows)
                    {
                        if (! row.entity() && ! row.stack().isEmpty())
                        {
                            stacks.add(row.stack());
                        }
                    }

                    StorageSearch.getInstance().searchAll(stacks);
                }
            });
        }
    }

    /** Выбранное размещение Litematica, иначе первое включённое. */
    private static @Nullable LitematicaSchematic currentSchematic()
    {
        try
        {
            SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

            if (selected != null && selected.getSchematic() != null)
            {
                return selected.getSchematic();
            }

            for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
            {
                if (placement.isEnabled() && placement.getSchematic() != null)
                {
                    return placement.getSchematic();
                }
            }
        }
        catch (RuntimeException e)
        {
            // Нет менеджера размещений (меню, смена мира) — подождём
        }

        return null;
    }
}
