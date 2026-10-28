package com.aell.layerlist.ghost;

import com.aell.layerlist.mixin.ghost.IMixinRenderSetup;
import com.aell.layerlist.mixin.ghost.IMixinRenderType;
import com.aell.layerlist.mixin.ghost.IMixinTextureBinding;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fi.dy.masa.litematica.config.Configs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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

    /** Квады блочной модели или предмета — своей геометрией в полупрозрачном слое атласа, по одному слою на атлас. */
    static void submitQuads(OrderedSubmitNodeCollector out, PoseStack pose, List<BakedQuad> quads, int[] tints,
            int light, int overlay, int alpha)
    {
        Map<Identifier, List<BakedQuad>> byAtlas = new LinkedHashMap<>();

        for (BakedQuad quad : quads)
        {
            byAtlas.computeIfAbsent(quad.materialInfo().sprite().atlasLocation(), k -> new ArrayList<>()).add(quad);
        }

        for (Map.Entry<Identifier, List<BakedQuad>> entry : byAtlas.entrySet())
        {
            List<BakedQuad> group = entry.getValue();
            RenderType type = ITEM_TYPES.computeIfAbsent(entry.getKey(), RenderTypes::itemTranslucent);

            out.submitCustomGeometry(pose, type, (matrix, consumer) -> {
                QuadInstance instance = new QuadInstance();

                for (BakedQuad quad : group)
                {
                    int tint = quad.materialInfo().tintIndex();
                    int color = tint >= 0 && tints != null && tint < tints.length ? tints[tint] : -1;
                    instance.setColor(withAlpha(color, alpha));
                    instance.setLightCoords(light);
                    instance.setOverlayCoords(overlay);
                    consumer.putBakedQuad(matrix, quad, instance);
                }
            });
        }
    }

    /**
     * Сетка Fabric Renderer API — вершинами в полупрозрачном слое блочного атласа. Данные копируются сразу:
     * сетка может быть переиспользуемым буфером, а своя геометрия рисуется позже.
     */
    static void submitMesh(OrderedSubmitNodeCollector out, PoseStack pose, Mesh mesh, int[] tints, int light, int overlay,
            int alpha)
    {
        List<float[]> quads = new ArrayList<>();
        List<int[]> colors = new ArrayList<>();

        mesh.forEach(quad -> {
            float[] v = new float[4 * 8];
            int[] c = new int[4];
            int tint = quad.tintIndex();

            for (int i = 0; i < 4; ++i)
            {
                v[i * 8] = quad.x(i);
                v[i * 8 + 1] = quad.y(i);
                v[i * 8 + 2] = quad.z(i);
                v[i * 8 + 3] = quad.u(i);
                v[i * 8 + 4] = quad.v(i);
                v[i * 8 + 5] = quad.normalX(i);
                v[i * 8 + 6] = quad.normalY(i);
                v[i * 8 + 7] = quad.normalZ(i);
                int color = quad.color(i);

                if (tint >= 0 && tints != null && tint < tints.length)
                {
                    color = ARGB.multiply(color, tints[tint]);
                }

                c[i] = withAlpha(color, alpha);
            }

            quads.add(v);
            colors.add(c);
        });

        if (quads.isEmpty())
        {
            return;
        }

        RenderType type = ITEM_TYPES.computeIfAbsent(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,
                RenderTypes::itemTranslucent);

        out.submitCustomGeometry(pose, type, (matrix, consumer) -> {
            for (int q = 0; q < quads.size(); ++q)
            {
                float[] v = quads.get(q);
                int[] c = colors.get(q);

                for (int i = 0; i < 4; ++i)
                {
                    consumer.addVertex(matrix, v[i * 8], v[i * 8 + 1], v[i * 8 + 2]).setColor(c[i])
                            .setUv(v[i * 8 + 3], v[i * 8 + 4]).setOverlay(overlay).setLight(light)
                            .setNormal(matrix, v[i * 8 + 5], v[i * 8 + 6], v[i * 8 + 7]);
                }
            }
        });
    }

    static List<BakedQuad> quads(List<BlockStateModelPart> parts)
    {
        List<BakedQuad> out = new ArrayList<>();

        for (BlockStateModelPart part : parts)
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
        public void submitNameTag(PoseStack pose, Vec3 offset, int y, Component name, boolean seeThrough, int light,
                CameraRenderState camera)
        {
            this.out.submitNameTag(pose, offset, y, name, seeThrough, light, camera);
        }

        @Override
        public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean shadow,
                Font.DisplayMode mode, int light, int color, int background, int outline)
        {
            this.out.submitText(pose, x, y, text, shadow, mode, light, color, background, outline);
        }

        @Override
        public void submitTextBackground(PoseStack pose, float x0, float y0, float x1, float y1, int color,
                Font.DisplayMode mode, int light)
        {
            this.out.submitTextBackground(pose, x0, y0, x1, y1, color, mode, light);
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
                int overlay, int tint, UvMapping uv, int outline)
        {
            this.out.submitModel(model, state, pose, translucent(type), light, overlay, withAlpha(tint, this.alpha), uv, outline);
        }

        @Override
        public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack pose, RenderType type,
                int light, int overlay, int tint, ModelFeatureRenderer.CrumblingOverlay crumbling)
        {
            this.out.submitCrumblingOverlay(model, state, pose, type, light, overlay, tint, crumbling);
        }

        @Override
        public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int light)
        {
            this.out.submitMovingBlock(pose, state, light);
        }

        @Override
        public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts, int[] tints,
                int light, int overlay, int outline)
        {
            submitQuads(this.out, pose, quads(parts), tints, light, overlay, this.alpha);
        }

        @Override
        public void submitBlockModel(PoseStack pose, Function<ChunkSectionLayer, RenderType> types, boolean translucentLayer,
                List<BlockStateModelPart> parts, @Nullable Mesh mesh, int[] tints, int light, int overlay, int outline)
        {
            // Путь Fabric Renderer API: рамка и прочие блочные модели приходят и частями, и сеткой
            submitQuads(this.out, pose, quads(parts), tints, light, overlay, this.alpha);

            if (mesh != null && mesh.size() > 0)
            {
                submitMesh(this.out, pose, mesh, tints, light, overlay, this.alpha);
            }
        }

        @Override
        public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress, boolean flag)
        {
            this.out.submitBreakingBlockModel(pose, parts, progress, flag);
        }

        @Override
        public void submitShapeOutline(PoseStack pose, VoxelShape shape, RenderType type, int color, float width,
                boolean seeThrough)
        {
            this.out.submitShapeOutline(pose, shape, type, color, width, seeThrough);
        }

        @Override
        public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline,
                int[] tints, ItemQuads quads, ItemStackRenderState.FoilType foil)
        {
            submitQuads(this.out, pose, quads.all(), tints, light, overlay, this.alpha);
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
        public void submitQuadParticleGroup(QuadParticleRenderState particles)
        {
            this.out.submitQuadParticleGroup(particles);
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag)
        {
            this.out.submitGizmoPrimitives(group, camera, flag);
        }

        @Override
        public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node)
        {
            this.out.submitCustom(phase, node);
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
        public VertexConsumer setUv3(float u, float v)
        {
            this.out.setUv3(u, v);
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
