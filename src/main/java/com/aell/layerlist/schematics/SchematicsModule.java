package com.aell.layerlist.schematics;

import com.aell.layerlist.schematics.gui.GuiSchematics;
import com.aell.layerlist.schematics.gui.PlacementHud;
import com.aell.layerlist.schematics.gui.SelectionHud;
import com.aell.layerlist.schematics.library.SchematicLibrary;
import com.aell.layerlist.schematics.library.SchematicTrash;
import com.aell.layerlist.schematics.materials.BlockReplacements;
import com.aell.layerlist.schematics.materials.ItemIcons;
import com.aell.layerlist.schematics.place.PlacementEditorScreen;
import com.aell.layerlist.schematics.place.PlacementOverlay;
import com.aell.layerlist.schematics.preview.PreviewModule;
import fi.dy.masa.malilib.util.GuiUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

/**
 * Вкладка «Схемы»: единая точка подключения к клиенту. Зовётся из
 * {@code LayerListClient.onInitializeClient} после загрузки конфига и регистрации хоткеев —
 * опции и хоткей фичи к этому моменту уже прочитаны из {@code layerlist.json}.
 *
 * <p>Сюда же модули фичи добавляют свои регистрации: рендерер превью (PIP), рендерер иконок выгрузок
 * (PIP), события мира для рамки размещения, флаги миксинов.
 */
@Environment(EnvType.CLIENT)
public final class SchematicsModule
{
    private static boolean initialized;

    private SchematicsModule() {}

    public static void init()
    {
        if (initialized)
        {
            return;
        }

        initialized = true;

        // 3D-превью: PIP-рендерер в реестре Fabric (только до создания GameRenderer) и подписка на сброс рендера
        PreviewModule.register();

        // Состояние экрана живёт в своём файле; сохраняем и при выходе из игры с открытым экраном
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SchematicsState.get().save());

        // Рамка размещения в мире (BEFORE_GIZMOS) и скрытие обводки блока в виде сверху
        PlacementOverlay.register();

        // «Смотреть на мир» вкладки «Выделение»: карточка координат HUD-ом, пока идёт игра
        SelectionHud.register();

        // То же для редактора размещения: карточка его координат, пока игрок ходит по миру
        PlacementHud.register();

        // Иконки выгрузок рисует игра в кадре GUI: рендерер в реестр PIP (тоже до GameRenderer) и такт для очереди
        ItemIcons.register();

        // F3+T и смена графики: иконки выгрузок нарисованы старыми текстурами
        InvalidateRenderStateCallback.EVENT.register(ItemIcons::clearCache);

        // Замены блоков «Материалов» (0.0.27): страховочный опрос схем размещений, если миксин чтения файла не встал
        BlockReplacements.register();

        // Корзина «Библиотеки»: старше 30 дней — насовсем, раз при старте (в фоне — это только файлы)
        // и ещё при каждом открытии экрана «Схемы»
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Util.ioPool().execute(SchematicsModule::purgeTrash));
    }

    private static void purgeTrash()
    {
        try
        {
            for (SchematicTrash.Item item : SchematicTrash.get().purgeExpired())
            {
                if (item.original() != null && ! item.directory())
                {
                    SchematicLibrary.get().dropThumbnailFiles(item.original());
                }
            }
        }
        catch (RuntimeException e)
        {
            // Не вышло сейчас — выйдет при открытии экрана «Схемы»
        }
    }

    /**
     * Свой полноэкранный экран вкладки: панель HUD поверх него не рисуется. В 26.3 оверлеи malilib идут
     * после экрана и легли бы на его кнопки («Готово»/«Отмена» редактора, карточку схемы).
     */
    public static boolean coversHud(@Nullable Screen screen)
    {
        return screen instanceof GuiSchematics || screen instanceof PlacementEditorScreen;
    }

    /**
     * Служебное сообщение Litematica, которое игроку на экранах вкладки ни о чём не говорит: «подсчёт
     * поставлен в очередь» приходит, когда кто-то заводит список материалов размещения.
     */
    public static boolean noiseMessage(String key)
    {
        return "litematica.message.scheduled_task_added".equals(key);
    }

    /** Хоткей {@code openSchematics}: экран поверх текущего, возврат — туда же. */
    public static void openFromHotkey()
    {
        GuiSchematics.open(GuiUtils.getCurrentScreen());
    }
}
