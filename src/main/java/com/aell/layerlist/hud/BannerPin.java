package com.aell.layerlist.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.render.GuiContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.LoomScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.Nullable;

/**
 * Закреплённый рецепт флага (просьба владельца 24.11.2026: «чтобы его можно было закрепить, например, в ткацком станке
 * через контекстное меню списка материалов»). Флаг закрепляют пунктом меню строки во вкладке «Материалы»; пока открыт
 * ткацкий станок, рядом с окном стоит его карточка ({@link BannerCardView}) — наводить не нужно. Карточка следит за
 * флагом в ячейке станка: сама открывает следующий шаг и помечает сделанные. Панель «Рецепты флагов у станка»
 * ({@link LoomGuide}) в это время молчит: карточка стоит на её месте и говорит то же подробнее.
 *
 * <p>Закреплён один флаг. Он переживает перезапуск игры: в {@code config/layerlist_banner_pin.json} лежат цвет основы
 * и узоры по именам реестра, а сам предмет собирается заново, когда есть мир (узоры — реестр мира).
 */
@Environment(EnvType.CLIENT)
public final class BannerPin
{
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("layerlist/banner-card");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Что лежит в файле: предмет-основа и слои «узор, цвет» по именам. */
    private record Saved(String item, List<String[]> layers) {}

    private static boolean loaded;
    private static @Nullable Saved saved;
    private static @Nullable ItemStack pinned;
    /** Мир, по реестру которого собран {@link #pinned}: другой мир — собрать заново. */
    private static @Nullable Object pinnedFor;
    private static @Nullable BannerCardView view;
    private static @Nullable Screen viewOn;

    private BannerPin() {}

    // ===== Что закреплено =====

    /** Закреплённый флаг; {@code null} — ничего не закреплено или его узоров нет в этом мире. */
    public static @Nullable ItemStack pinned()
    {
        load();

        if (saved == null)
        {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return null;
        }

        if (pinned == null || pinnedFor != mc.level)
        {
            pinned = rebuild(saved);
            pinnedFor = mc.level;
        }

        return pinned;
    }

    public static boolean is(ItemStack stack)
    {
        ItemStack now = pinned();
        return now != null && ItemStack.isSameItemSameComponents(now, stack);
    }

    /** Закрепить флаг с узорами; прежний открепляется. */
    public static void pin(ItemStack stack)
    {
        if (! BannerCard.patterned(stack))
        {
            return;
        }

        load();
        List<String[]> layers = new ArrayList<>();

        for (BannerPatternLayers.Layer layer : stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers())
        {
            Optional<ResourceKey<BannerPattern>> key = layer.pattern().unwrapKey();

            if (key.isEmpty())
            {
                // Узор без имени в реестре (вписан прямо в предмет) файл не переживёт — закрепляем только на эту игру
                layers = null;
                break;
            }

            layers.add(new String[]{key.get().identifier().toString(), layer.color().getName()});
        }

        pinned = stack.copyWithCount(1);
        pinnedFor = Minecraft.getInstance().level;
        saved = new Saved(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), layers == null ? List.of() : layers);
        view = null;
        // Узор без имени в реестре в файл не записать: файл убираем, чтобы после перезапуска не вернулся прежний флаг
        save();
    }

    public static void unpin()
    {
        load();
        saved = null;
        pinned = null;
        pinnedFor = null;
        view = null;
        viewOn = null;
        save();
    }

    /** Предмет по записи файла; узора или цвета в этом мире нет — {@code null} (запись остаётся). */
    private static @Nullable ItemStack rebuild(Saved from)
    {
        Minecraft mc = Minecraft.getInstance();
        Identifier itemId = Identifier.tryParse(from.item());
        Item item = itemId == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(itemId);

        if (! (item instanceof BannerItem) || mc.level == null || from.layers().isEmpty())
        {
            return null;
        }

        try
        {
            var patterns = mc.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
            List<BannerPatternLayers.Layer> layers = new ArrayList<>();

            for (String[] layer : from.layers())
            {
                Identifier id = Identifier.tryParse(layer[0]);
                DyeColor color = DyeColor.byName(layer[1], null);
                Optional<? extends Holder<BannerPattern>> pattern = id == null ? Optional.empty()
                        : patterns.get(ResourceKey.create(Registries.BANNER_PATTERN, id));

                if (pattern.isEmpty() || color == null)
                {
                    return null;
                }

                layers.add(new BannerPatternLayers.Layer(pattern.get(), color));
            }

            ItemStack stack = new ItemStack(item);
            stack.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(layers));
            return stack;
        }
        catch (RuntimeException e)
        {
            LOGGER.warn("Pinned banner could not be rebuilt for this world", e);
            return null;
        }
    }

    // ===== Файл =====

    private static Path file()
    {
        return FabricLoader.getInstance().getConfigDir().resolve("layerlist_banner_pin.json");
    }

    private static void load()
    {
        if (loaded)
        {
            return;
        }

        loaded = true;
        Path file = file();

        if (! Files.isRegularFile(file))
        {
            return;
        }

        try
        {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

            if (! root.has("item") || ! root.has("layers"))
            {
                return;
            }

            List<String[]> layers = new ArrayList<>();

            for (var element : root.getAsJsonArray("layers"))
            {
                JsonObject layer = element.getAsJsonObject();
                layers.add(new String[]{layer.get("pattern").getAsString(), layer.get("color").getAsString()});
            }

            saved = new Saved(root.get("item").getAsString(), layers);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.warn("Pinned banner file is unreadable, ignoring it: {}", file, e);
        }
    }

    private static void save()
    {
        Path file = file();

        try
        {
            if (saved == null || saved.layers().isEmpty())
            {
                Files.deleteIfExists(file);
                return;
            }

            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("item", saved.item());
            JsonArray layers = new JsonArray();

            for (String[] layer : saved.layers())
            {
                JsonObject entry = new JsonObject();
                entry.addProperty("pattern", layer[0]);
                entry.addProperty("color", layer[1]);
                layers.add(entry);
            }

            root.add("layers", layers);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOGGER.warn("Pinned banner could not be saved: {}", file, e);
        }
    }

    // ===== У ткацкого станка =====

    /**
     * Сейчас у этого окна стоит (или встанет в этом кадре) закреплённая карточка. От настройки «Рецепт флага при
     * наведении» не зависит: закрепили явно — показываем.
     */
    public static boolean shows(@Nullable Screen screen)
    {
        return screen instanceof LoomScreen && ! BannerCard.broken() && pinned() != null;
    }

    /** Из {@link BannerCard#render}: до карточки под курсором, та ложится поверх. */
    static void render(GuiGraphicsExtractor graphics, Screen screen, int mouseX, int mouseY)
    {
        if (! shows(screen))
        {
            view = null;
            viewOn = null;
            return;
        }

        LoomScreen loom = (LoomScreen) screen;
        ItemStack stack = pinned();

        if (view == null || viewOn != screen || ! ItemStack.isSameItemSameComponents(view.stack(), stack))
        {
            view = new BannerCardView(stack).pinned(BannerPin::unpin);
            viewOn = screen;
        }

        // Там же, где встала бы панель «Рецепты флагов у станка»: с другой стороны окна, чем панель материалов
        HudDock.Dock dock = LoomGuide.place(screen, BannerCardView.W, BannerCardView.H, 1.0D);

        if (dock == null)
        {
            return;
        }

        BannerCardView shown = view;
        shown.place(dock.x(), dock.y(), (float) dock.scale());
        shown.follow(made(stack, loom.getMenu().getBannerSlot().getItem()));
        shown.draw(GuiContext.fromGuiGraphics(graphics), Minecraft.getInstance().font, mouseX, mouseY);
    }

    /** Сколько узоров закреплённого флага уже нанесено у флага в ячейке станка; −1 — там не он. */
    static int made(ItemStack banner, ItemStack inLoom)
    {
        LoomGuide.Recipe recipe = new LoomGuide.Recipe(banner, ((BannerItem) banner.getItem()).getColor(),
                banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers(), 0L);
        return LoomGuide.done(recipe, inLoom) - 1;
    }

    /**
     * Карточка под курсором здесь не нужна: наведён тот же флаг, что закреплён (его карточка уже стоит рядом), или
     * курсор на самой закреплённой карточке.
     */
    static boolean covers(Screen screen, ItemStack over, int mouseX, int mouseY)
    {
        BannerCardView shown = view;

        if (shown == null || viewOn != screen)
        {
            return false;
        }

        return shown.contains(mouseX, mouseY) || ItemStack.isSameItemSameComponents(shown.stack(), over);
    }

    static boolean mouseClicked(@Nullable Screen screen, double mouseX, double mouseY, int button)
    {
        BannerCardView shown = view;
        return screen != null && shown != null && viewOn == screen && shown.click(mouseX, mouseY, button);
    }

    /** Колёсико листает закреплённую карточку, только когда курсор на ней: у станка оно листает список узоров. */
    static boolean mouseScrolled(Screen screen, double mouseX, double mouseY, double amount)
    {
        BannerCardView shown = view;

        if (shown == null || viewOn != screen || ! shown.contains(mouseX, mouseY))
        {
            return false;
        }

        shown.turn(amount < 0.0D ? 1 : - 1);
        return true;
    }

    static boolean keyPressed(Screen screen, int key)
    {
        BannerCardView shown = view;
        return shown != null && viewOn == screen && shown.key(key);
    }

    /** Закреплённая карточка стоит рядом с окном, ячеек под ней нет — подсказки она не заслоняет. */
    static boolean hidesTooltip(Screen screen)
    {
        return false;
    }

    // ===== Для стенда =====

    /** Только для харнесса. В игре никто не зовёт. */
    public static final class TestHooks
    {
        private TestHooks() {}

        /** {x, y, ширина, высота} закреплённой карточки на экране или {@code null}, если она не показана. */
        public static int @Nullable [] bounds()
        {
            BannerCardView shown = view;
            return shown == null ? null : new int[]{shown.x(), shown.y(), shown.width(), shown.height()};
        }

        public static String state()
        {
            BannerCardView shown = view;
            return shown == null ? "нет" : shown.state() + ", сделано шагов " + shown.made();
        }

        /** Середина крестика «открепить» на экране. */
        public static double @Nullable [] unpinSpot()
        {
            BannerCardView shown = view;
            return shown == null ? null : shown.unpinSpot();
        }

        /** Забыть собранный предмет и перечитать файл — как после перезапуска игры. */
        public static void reload()
        {
            loaded = false;
            saved = null;
            pinned = null;
            pinnedFor = null;
            view = null;
            viewOn = null;
        }
    }
}
