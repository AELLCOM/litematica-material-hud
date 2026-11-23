package com.aell.layerlist;

import com.aell.layerlist.config.LayerListConfigs;
import com.aell.layerlist.core.LayerCountManager;
import com.aell.layerlist.core.VerifyCache;
import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.ContainerClick;
import com.aell.layerlist.easyplace.DeferredPlace;
import com.aell.layerlist.easyplace.FollowUp;
import com.aell.layerlist.easyplace.ForeignSuppressor;
import com.aell.layerlist.ghost.LeanInnerSides;
import com.aell.layerlist.hud.LayerHudRenderer;
import com.aell.layerlist.input.InputHandler;
import com.aell.layerlist.schematics.SchematicsModule;
import com.aell.layerlist.storage.ContainerObserver;
import com.aell.layerlist.storage.ItemColors;
import com.aell.layerlist.storage.StorageHighlightRenderer;
import com.aell.layerlist.storage.StorageIndex;
import com.aell.layerlist.storage.StorageSearch;
import com.aell.layerlist.shelf.ShelfIndex;
import com.aell.layerlist.shelf.ShelfRenderer;
import com.aell.layerlist.verify.LayerVerifier;
import com.aell.layerlist.verify.LayerVerifyRenderer;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import java.lang.reflect.Field;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.InteractionResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class LayerListClient implements ClientModInitializer
{
    private static int suppressCheckTicks;
    private static final Logger LOGGER = LoggerFactory.getLogger("layerlist");
    private static @Nullable Field materialListField;
    private static boolean materialListLookedUp;

    @Override
    public void onInitializeClient()
    {
        DevScreenshots.register();
        registerCoverageHarness();

        LayerListConfigs config = new LayerListConfigs();
        config.load();
        ConfigManager.getInstance().registerConfigHandler("layerlist", config);
        // Ванильный стиль интерфейса — по настройке мода; в общем модуле он выключен, пока мод его не включит
        com.aell.layerlist.shaded.ui.Style.levelWhen(LayerListConfigs.UI_STYLE::getIntegerValue, false);

        LayerListConfigs.GHOST_LEAN_INNER_SIDES.setValueChangeCallback(
                changed -> LeanInnerSides.refreshRenderers());
        LayerListConfigs.GHOST_INVENTORY_ONLY.setValueChangeCallback(
                changed -> com.aell.layerlist.ghost.InventoryGhosts.onModeToggled(changed.getBooleanValue()));
        LayerListConfigs.GHOST_HIDE_PORTALS.setValueChangeCallback(
                changed -> com.aell.layerlist.ghost.InventoryGhosts.refreshRenderers());
        // Обе опции опоры действуют и на список HUD (19.11.2026) — он пересчитывается сразу, а не по таймеру
        LayerListConfigs.GHOST_HIDE_UNSUPPORTED.setValueChangeCallback(changed ->
        {
            com.aell.layerlist.ghost.InventoryGhosts.refreshRenderers();
            LayerCountManager.getInstance().markDirty();
        });
        LayerListConfigs.GHOST_FLAT_NO_FILL.setValueChangeCallback(
                changed -> com.aell.layerlist.ghost.InventoryGhosts.refreshRenderers());
        LayerListConfigs.GHOST_SUPPORT_FROM_LAYERS.setValueChangeCallback(changed ->
        {
            com.aell.layerlist.ghost.GhostSupport.markDirty();
            LayerCountManager.getInstance().markDirty();
        });

        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputHandler.getInstance().registerKeyCallbacks();

        com.aell.layerlist.shaded.ui.ModSwitcher.register("layerlist", "Litematica Material HUD",
                () -> com.aell.layerlist.config.GuiConfigs.create(null, null));

        // После конфига и хоткеев: опции и хоткей вкладки «Схемы» уже прочитаны и привязаны
        SchematicsModule.init();

        StorageIndex.getInstance().load();
        com.aell.layerlist.storage.ChosenStorages.getInstance().load();
        // Сменились правила счёта склада — панель и «В сундуках» пересчитываются по ревизии индекса, подсветка поиска
        // тоже: со «Своим складом» отметки только на выбранных
        LayerListConfigs.COUNT_CHOSEN_CHESTS.setValueChangeCallback(changed ->
        {
            StorageIndex.getInstance().touch();
            com.aell.layerlist.storage.StorageSearch.getInstance().requestRefresh();
        });
        LayerListConfigs.COUNT_ENDER_CHEST.setValueChangeCallback(changed ->
        {
            StorageIndex.getInstance().touch();
            com.aell.layerlist.storage.StorageSearch.getInstance().requestRefresh();
        });
        LayerListConfigs.STOCK_NEAR_ONLY.setValueChangeCallback(changed -> com.aell.layerlist.storage.StockArea.changed());
        LayerListConfigs.STOCK_RADIUS.setValueChangeCallback(changed -> com.aell.layerlist.storage.StockArea.changed());
        LayerListConfigs.STOCK_CHEST_TRACKER.setValueChangeCallback(changed ->
        {
            com.aell.layerlist.compat.ChestTracker.changed();
            com.aell.layerlist.storage.StorageSearch.getInstance().requestRefresh();
        });
        registerColorCacheReset();

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide())
            {
                // Клик по контейнеру встаёт в очередь не здесь, а на выходе useItemOn
                // (MixinMultiPlayerGameModeStorage): сюда приходят и клики, которые потом гасит Easy Place
                LayerCountManager.getInstance().markDirty();
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide()) { ContainerObserver.onNonBlockInteraction(); }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) { LayerCountManager.getInstance().markDirty(); }
            return InteractionResult.PASS;
        });

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            StorageHighlightRenderer.render();
            com.aell.layerlist.storage.ChestPicker.render();
            LayerVerifyRenderer.render();
            ShelfRenderer.render();
            com.aell.layerlist.ghost.GhostSupport.renderFrames();
            com.aell.layerlist.ghost.EntityOverlay.render();
        });

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                LayerVerifier.getInstance().onChunkLoaded(chunk.getPos().x, chunk.getPos().z));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ContainerObserver.saveAndClearActive(client);
            com.aell.layerlist.storage.StorageIndex.getInstance().flush();
            VerifyCache.getInstance().save(true);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            LayerCountManager.getInstance().onClientTick(client);
            ContainerObserver.onClientTick(client);
            com.aell.layerlist.storage.ChestPicker.onClientTick(client);
            com.aell.layerlist.compat.ChestTracker.onClientTick(client);
            com.aell.layerlist.storage.StockArea.onClientTick(client);
            com.aell.layerlist.storage.AllChestsSearch.tick(client);
            StorageSearch.getInstance().onClientTick(client);
            LayerVerifier.getInstance().onClientTick(client);
            com.aell.layerlist.ghost.InventoryGhosts.onClientTick(client);
            com.aell.layerlist.ghost.GhostSupport.onClientTick(client);
            com.aell.layerlist.ghost.EntityOverlay.onClientTick(client);
            com.aell.layerlist.hud.TransferButton.onClientTick(client);
            ShelfIndex.getInstance().onClientTick(client);
            ForeignSuppressor.onClientTick(client);
            AutoPlaceState.onClientTick(client);
            com.aell.layerlist.easyplace.PoseAhead.onClientTick(client);
            ContainerClick.onClientTick(client);
            DeferredPlace.onClientTick(client);
            FollowUp.onClientTick(client);
            com.aell.layerlist.easyplace.SoilDig.onClientTick(client);

            if (client.level == null || ! LayerCountManager.getInstance().isHudEnabled())
            {
                return;
            }

            InfoHud.getInstance().addInfoHudRenderer(LayerHudRenderer.getInstance(), false);

            if (LayerListConfigs.REPLACE_LITEMATICA_HUD.getBooleanValue() && ++suppressCheckTicks >= 10)
            {
                suppressCheckTicks = 0;
                suppressLitematicaMaterialHud(DataManager.getMaterialList());

                for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
                {
                    suppressLitematicaMaterialHud(existingMaterialList(placement));
                }
            }
        });
    }

    /**
     * Харнесс покрытия Easy Place живёт в наборе исходников {@code src/dev/java} и в
     * релизный jar не попадает, поэтому цепляется отражением и только по переменной
     * окружения. Без неё метод не делает ничего.
     */
    private static void registerCoverageHarness()
    {
        if (System.getenv("LL_COVERAGE") == null)
        {
            return;
        }

        try
        {
            Class.forName("com.aell.layerlist.dev.Coverage").getMethod("register").invoke(null);
        }
        catch (ReflectiveOperationException | LinkageError e)
        {
            System.out.println("[COVERAGE] харнесс не найден на пути запуска: " + e);
        }
    }

    private static void registerColorCacheReset()
    {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener()
                {
                    @Override
                    public Identifier getFabricId()
                    {
                        return Identifier.fromNamespaceAndPath("layerlist", "item_colors");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager)
                    {
                        ItemColors.clearCache();
                    }
                });
    }

    /**
     * Список материалов размещения, только если Litematica его уже завела. {@code getMaterialList()} заводит
     * его сам и тут же ставит подсчёт блоков всего размещения в мире (сообщение «Scheduled task added…»
     * посреди экрана) — на каждое новое размещение, в том числе из вкладки «Схемы». Скрывать нечего,
     * пока списка нет; поле не читается — размещения пропускаем (список из {@code DataManager} — всё равно).
     */
    private static @Nullable MaterialListBase existingMaterialList(SchematicPlacement placement)
    {
        if (! materialListLookedUp)
        {
            materialListLookedUp = true;

            try
            {
                Field field = SchematicPlacement.class.getDeclaredField("materialList");
                field.setAccessible(true);
                materialListField = field;
            }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                LOGGER.warn("Litematica SchematicPlacement.materialList is not readable; placement material list HUDs are left as they are");
            }
        }

        try
        {
            return materialListField != null ? (MaterialListBase) materialListField.get(placement) : null;
        }
        catch (IllegalAccessException | ClassCastException e)
        {
            return null;
        }
    }

    private static void suppressLitematicaMaterialHud(@Nullable MaterialListBase materialList)
    {
        if (materialList != null)
        {
            MaterialListHudRenderer renderer = materialList.getHudRenderer();

            if (renderer.getShouldRenderCustom())
            {
                renderer.toggleShouldRender();
                InfoHud.getInstance().removeInfoHudRenderer(renderer, false);
            }
        }
    }
}
