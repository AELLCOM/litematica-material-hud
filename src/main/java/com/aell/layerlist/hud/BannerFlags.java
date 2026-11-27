package com.aell.layerlist.hud;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.malilib.render.GuiContext;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.jetbrains.annotations.Nullable;

/**
 * Несколько полотен флага в одном кадре GUI — для карточки {@link BannerCard}.
 *
 * <p>Игра рисует полотно «картинкой в картинке» ({@code GuiGraphicsExtractor.bannerPattern}, как в окне ткацкого
 * станка), но у неё одна текстура на вид картинки: второе полотно в том же кадре затёрло бы первое. Здесь свой вид
 * картинки — вся область страницы карточки, и все её полотна рисуются в неё за один проход тем же способом, что и
 * у станка: модель полотна, основа и слои узора ({@code BannerRenderer.submitPatterns}), свет предметов.
 *
 * <p>Картинок две — у каждой свой вид заявки: у ткацкого станка в одном кадре могут стоять сразу две карточки,
 * закреплённая ({@link BannerPin}) и открытая под курсором, и одна затёрла бы полотна другой.
 */
@Environment(EnvType.CLIENT)
public final class BannerFlags
{
    /** Свет «полная яркость» — как у полотна в окне станка. */
    private static final int FULL_BRIGHT = 15728880;
    /** У игры полотно шириной 20 пикселей GUI рисуется с масштабом 16. */
    private static final float SCALE_PER_WIDTH = 16.0F / 20.0F;
    private static boolean registered;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("layerlist/banner-card");
    /** Отрисовка полотен упала (другой драйвер, другой API игры): дальше карточка идёт без картинок флагов. */
    private static volatile boolean failed;

    /** Одно полотно: цвет основы, слои и прямоугольник (ширина к высоте 1:2) от левого верха области. */
    public record Flag(DyeColor base, BannerPatternLayers layers, int x, int y, int width, int height) {}

    /**
     * Заявка на кадр: область в GUI и полотна в ней. Единица позы — пиксель GUI ({@code scale} = 1). {@code pose} — поза
     * GUI в момент заявки: карточка у станка рисуется сдвинутой и, на узком экране, ужатой — картинка ложится так же.
     */
    sealed interface Frame extends PictureInPictureRenderState permits State, PinnedState
    {
        BannerFlagModel model();

        List<Flag> flags();

        @Override
        default float scale()
        {
            return 1.0F;
        }
    }

    /** Картинка карточки под курсором. */
    record State(BannerFlagModel model, List<Flag> flags, int x0, int y0, int x1, int y1, org.joml.Matrix3x2fc pose,
            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements Frame {}

    /** Картинка закреплённой карточки — отдельный вид, чтобы не делить текстуру с карточкой под курсором. */
    record PinnedState(BannerFlagModel model, List<Flag> flags, int x0, int y0, int x1, int y1, org.joml.Matrix3x2fc pose,
            @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements Frame {}

    private BannerFlags() {}

    /** Один раз из {@code onInitializeClient}: отрисовщик создаётся вместе с отрисовщиком GUI игры. */
    public static void register()
    {
        if (! registered)
        {
            registered = true;
            PictureInPictureRendererRegistry.register(context -> new Renderer<>(State.class, "layerlist_banner_flags"));
            PictureInPictureRendererRegistry.register(context -> new Renderer<>(PinnedState.class, "layerlist_banner_flags_pinned"));
        }
    }

    /**
     * Нарисовать полотна в области {@code x..x+width, y..y+height}; координаты полотен — от её левого верха.
     *
     * @param pinned картинка закреплённой карточки (своя текстура), иначе — карточки под курсором
     */
    public static void draw(GuiContext ctx, BannerFlagModel model, List<Flag> flags, int x, int y, int width, int height,
            boolean pinned)
    {
        if (flags.isEmpty() || failed)
        {
            return;
        }

        ScreenRectangle scissor = ctx.peekLastScissor();
        org.joml.Matrix3x2f pose = new org.joml.Matrix3x2f(ctx.pose());
        // 26.2: у игры нет getBounds с позой — то же самое руками (как в 26.3)
        ScreenRectangle box = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
        ScreenRectangle bounds = scissor != null ? scissor.intersection(box) : box;
        List<Flag> copy = List.copyOf(flags);
        ctx.addSpecialElement(pinned ? new PinnedState(model, copy, x, y, x + width, y + height, pose, scissor, bounds)
                : new State(model, copy, x, y, x + width, y + height, pose, scissor, bounds));
    }

    private static final class Renderer<S extends Frame> extends PictureInPictureRenderer<S>
    {
        private final Class<S> type;
        private final String label;

        private Renderer(Class<S> type, String label)
        {
            this.type = type;
            this.label = label;
        }

        @Override
        public Class<S> getRenderStateClass()
        {
            return this.type;
        }

        @Override
        protected String getTextureLabel()
        {
            return this.label;
        }

        @Override
        protected void renderToTexture(S state, PoseStack pose, SubmitNodeCollector collector)
        {
            // Кадр GUI на клиентском потоке: исключение отсюда уронило бы игру, а картинка флага — не повод
            try
            {
                this.submit(state, pose, collector);
            }
            catch (RuntimeException | LinkageError e)
            {
                if (! failed)
                {
                    failed = true;
                    LOGGER.warn("Banner flags failed to draw; the recipe card goes on without flag pictures", e);
                }
            }
        }

        private void submit(S state, PoseStack pose, SubmitNodeCollector collector)
        {
            Minecraft mc = Minecraft.getInstance();
            mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT);
            SpriteGetter sprites = mc.getAtlasManager();
            float areaWidth = state.x1() - state.x0();
            float areaHeight = state.y1() - state.y0();

            for (Flag flag : state.flags())
            {
                // Начало позы — середина нижнего края области, Y растёт вниз; у полотна оно — в середине его нижнего края
                float scale = flag.width() * SCALE_PER_WIDTH;
                pose.pushPose();
                pose.translate(flag.x() + flag.width() / 2.0F - areaWidth / 2.0F, flag.y() + flag.height() - areaHeight, 0.0F);
                pose.scale(scale, scale, scale);
                pose.translate(0.0F, 0.25F, 0.0F);
                collector.submitModel(state.model(), 0.0F, pose, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1, Sheets.BANNER_BASE,
                        sprites, 0, null);
                BannerRenderer.submitPatterns(sprites, pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, state.model(), 0.0F,
                        true, flag.base(), flag.layers(), null);
                pose.popPose();
            }
        }
    }
}
