package com.aell.layerlist.ghost;

import com.aell.layerlist.mixin.ghost.IMixinRenderSetup;
import com.aell.layerlist.mixin.ghost.IMixinRenderType;
import com.aell.layerlist.mixin.ghost.IMixinTextureBinding;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fi.dy.masa.litematica.config.Configs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

/**
 * Голограммы сущностей и блок-сущностей схемы — полупрозрачные, как голограммы блоков (жалоба 10.10.2026:
 * «подушки, рамки отображаются как блоки» — до установки их не отличить от настоящих).
 *
 * <p>Litematica рисует их обычным рендером в общий {@link SubmitNodeCollector}; подменяем его обёрткой:
 * <ul>
 *   <li>модели (подушка, стойка, вагонетка, лодка, кристалл, сундук…) — полупрозрачным двойником своего
 *       {@link RenderType} (та же текстура) с альфой в оттенке;</li>
 *   <li>блочные модели (рамка) и предметы (предмет в рамке, блок в вагонетке) — своими квадами в полупрозрачном
 *       слое их атласа, с альфой в каждой вершине;</li>
 *   <li>своя геометрия (картина) — полупрозрачным типом, альфа — в каждой вершине;</li>
 *   <li>тени у голограмм не рисуются; надписи, частицы и прочее — как есть.</li>
 * </ul>
 * Прозрачность — та же, что у голограмм блоков («Ghost Alpha» Litematica), и только когда она включена.
 *
 * <p>1.21.11: набор методов {@link OrderedSubmitNodeCollector} свой (блочная модель — {@code BlockStateModel}
 * с цветом r/g/b, части модели — {@code submitModelPart}), квады кладутся ванильным {@code putBulkData}.
 */
@Environment(EnvType.CLIENT)
public final class GhostSubmits
{
    private static final Map<Identifier, RenderType> ENTITY_TYPES = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderType> ITEM_TYPES = new ConcurrentHashMap<>();

    private GhostSubmits() {}

    /** Обёртка для отрисовки голограмм; прозрачность выключена — приёмник как есть. */
    public static SubmitNodeCollector wrap(SubmitNodeCollector queue)
    {
        int alpha = alpha();
        return alpha >= 255 || queue instanceof Root ? queue : new Root(queue, alpha);
    }

    /** Альфа голограмм 0…255: прозрачность Litematica включена — её «Ghost Alpha», иначе 255 (непрозрачно). */
    static int alpha()
    {
        try
        {
            if (! Configs.Visuals.RENDER_BLOCKS_AS_TRANSLUCENT.getBooleanValue())
            {
                return 255;
            }

            return Math.max(16, Math.min(255, (int) Math.round(Configs.Visuals.GHOST_BLOCK_ALPHA.getDoubleValue() * 255.0D)));
        }
        catch (RuntimeException | LinkageError e)
        {
            return 255;
        }
    }

    /** Полупрозрачный двойник: та же текстура, тип с прозрачностью. Текстуру не узнать — исходный тип. */
    static RenderType translucent(RenderType type)
    {
        Identifier texture = texture(type);
        return texture == null ? type : ENTITY_TYPES.computeIfAbsent(texture, RenderTypes::entityTranslucent);
    }

    private static @Nullable Identifier texture(RenderType type)
    {
        try
        {
            Map<String, ?> textures = ((IMixinRenderSetup) (Object) ((IMixinRenderType) type).layerlist$state()).layerlist$textures();
            Object binding = textures.get("Sampler0");

            if (binding == null && ! textures.isEmpty())
            {
                binding = textures.values().iterator().next();
            }

            return binding == null ? null : ((IMixinTextureBinding) binding).layerlist$location();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }

    static int withAlpha(int argb, int alpha)
    {
        return ARGB.color(ARGB.alpha(argb) * alpha / 255, argb);
    }

    /**
     * Квады блочной модели или предмета — своей геометрией в полупрозрачном слое атласа, по одному слою на атлас.
     * Оттенок квада: {@code tints[tintIndex]}, а при {@code tints == null} — один цвет {@code tint} на все тонируемые.
     */
    static void submitQuads(OrderedSubmitNodeCollector out, PoseStack pose, List<BakedQuad> quads, int @Nullable [] tints,
            int tint, int light, int overlay, int alpha)
    {
        Map<Identifier, List<BakedQuad>> byAtlas = new LinkedHashMap<>();

        for (BakedQuad quad : quads)
        {
            byAtlas.computeIfAbsent(quad.sprite().atlasLocation(), k -> new ArrayList<>()).add(quad);
        }

        for (Map.Entry<Identifier, List<BakedQuad>> entry : byAtlas.entrySet())
        {
            List<BakedQuad> group = entry.getValue();
            RenderType type = ITEM_TYPES.computeIfAbsent(entry.getKey(), RenderTypes::itemEntityTranslucentCull);

            out.submitCustomGeometry(pose, type, (matrix, consumer) -> {
                for (BakedQuad quad : group)
                {
                    int index = quad.tintIndex();
                    int color = index < 0 ? -1 : tints == null ? tint : index < tints.length ? tints[index] : -1;

                    consumer.putBulkData(matrix, quad, ARGB.redFloat(color), ARGB.greenFloat(color), ARGB.blueFloat(color),
                            alpha / 255.0F, light, overlay);
                }
            });
        }
    }

    static List<BakedQuad> quads(BlockStateModel model)
    {
        List<BakedQuad> out = new ArrayList<>();

        for (BlockModelPart part : model.collectParts(RandomSource.create(42L)))
        {
            for (Direction side : Direction.values())
            {
                out.addAll(part.getQuads(side));
            }

            out.addAll(part.getQuads(null));
        }

        return out;
    }

    /** Приёмник по порядку отрисовки ({@code order(i)}) с той же подменой. */
    static class Ordered implements OrderedSubmitNodeCollector
    {
        final OrderedSubmitNodeCollector out;
        final int alpha;

        Ordered(OrderedSubmitNodeCollector out, int alpha)
        {
            this.out = out;
            this.alpha = alpha;
        }

        @Override
        public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces)
        {
            // У голограммы тени нет
        }

        @Override
        public void submitNameTag(PoseStack pose, @Nullable Vec3 offset, int y, Component name, boolean seeThrough, int light,
                double distance, CameraRenderState camera)
        {
            this.out.submitNameTag(pose, offset, y, name, seeThrough, light, distance, camera);
        }

        @Override
        public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow,
                Font.DisplayMode mode, int light, int color, int background, int outline)
        {
            this.out.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
        }

        @Override
        public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation)
        {
            this.out.submitFlame(pose, state, rotation);
        }

        @Override
        public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash)
        {
            this.out.submitLeash(pose, leash);
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light,
                int overlay, int tint, @Nullable TextureAtlasSprite sprite, int outline,
                ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling)
        {
            this.out.submitModel(model, state, pose, translucent(type), light, overlay, withAlpha(tint, this.alpha), sprite,
                    outline, crumbling);
        }

        @Override
        public void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay,
                @Nullable TextureAtlasSprite sprite, boolean sheeted, boolean foil, int tint,
                ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling, int outline)
        {
            this.out.submitModelPart(part, pose, translucent(type), light, overlay, sprite, sheeted, foil,
                    withAlpha(tint, this.alpha), crumbling, outline);
        }

        @Override
        public void submitBlock(PoseStack pose, BlockState state, int light, int overlay, int outline)
        {
            BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            int tint = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
            submitQuads(this.out, pose, quads(model), null, tint == -1 ? -1 : 0xFF000000 | tint, light, overlay, this.alpha);
        }

        @Override
        public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state)
        {
            this.out.submitMovingBlock(pose, state);
        }

        @Override
        public void submitBlockModel(PoseStack pose, RenderType type, BlockStateModel model, float r, float g, float b,
                int light, int overlay, int outline)
        {
            submitQuads(this.out, pose, quads(model), null, ARGB.colorFromFloat(1.0F, r, g, b), light, overlay, this.alpha);
        }

        @Override
        public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                int[] tints, List<BakedQuad> quads, RenderType type, ItemStackRenderState.FoilType foil)
        {
            submitQuads(this.out, pose, quads, tints, -1, light, overlay, this.alpha);
        }

        @Override
        public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer)
        {
            RenderType faded = translucent(type);

            // Двойник — только с тем же форматом вершин: портал и шлюз Энда пишут одни координаты, а полупрозрачный тип
            // сущностей ждёт ещё текстуру, свет и нормаль — буфер падал на недописанной вершине (краш 20.10.2026)
            if (! faded.format().equals(type.format()))
            {
                this.out.submitCustomGeometry(pose, type, renderer);
                return;
            }

            int alpha = this.alpha;
            this.out.submitCustomGeometry(pose, faded, (matrix, consumer) -> renderer.render(matrix, new Faded(consumer, alpha)));
        }

        @Override
        public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particles)
        {
            this.out.submitParticleGroup(particles);
        }
    }

    /** Корневой приёмник: то же плюс {@code order(i)}. */
    static final class Root extends Ordered implements SubmitNodeCollector
    {
        private final SubmitNodeCollector root;

        Root(SubmitNodeCollector root, int alpha)
        {
            super(root, alpha);
            this.root = root;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order)
        {
            return new Ordered(this.root.order(order), this.alpha);
        }
    }

    /** Вершины с уменьшенной альфой — для своей геометрии (картина). */
    static final class Faded implements VertexConsumer
    {
        private final VertexConsumer out;
        private final int alpha;

        Faded(VertexConsumer out, int alpha)
        {
            this.out = out;
            this.alpha = alpha;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            this.out.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a)
        {
            this.out.setColor(r, g, b, a * this.alpha / 255);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb)
        {
            this.out.setColor(withAlpha(argb, this.alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            this.out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            this.out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            this.out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z)
        {
            this.out.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width)
        {
            this.out.setLineWidth(width);
            return this;
        }
    }
}
