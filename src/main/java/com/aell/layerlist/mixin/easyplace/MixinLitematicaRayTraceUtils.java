package com.aell.layerlist.mixin.easyplace;

import com.aell.layerlist.easyplace.AirTarget;
import com.aell.layerlist.easyplace.AutoPlaceState;
import com.aell.layerlist.easyplace.EasyPlaceModule;
import com.aell.layerlist.easyplace.SchematicTarget;
import fi.dy.masa.litematica.util.EasyPlaceUtils;
import fi.dy.masa.litematica.util.RayTraceUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Наведение на голограмму, в контур которой не попасть.
 *
 * Litematica ведёт луч по схематическому миру теми же контурами, что и по обычному
 * ({@code blockState.getShape}), и берёт <b>ближайшее</b> попадание. У цветка, лозы,
 * лишайника и саженца контур — крестик или тонкая пластина: луч проходит мимо него и
 * попадает в блок за ним, а этот блок в схеме обычно уже стоит. Litematica видит
 * «клетка уже такая, как в схеме» и выходит с FAIL — со стороны это выглядит так,
 * будто Easy Place не работает вовсе.
 *
 * Поэтому вмешиваемся ровно тогда, когда наведённой клетке делать нечего: если луч
 * прошёл сквозь клетку, которую схема просит заполнить, но мимо её голограммы, целью
 * становится она.
 */
@Environment(EnvType.CLIENT)
@Mixin(value = RayTraceUtils.class, remap = false)
public abstract class MixinLitematicaRayTraceUtils
{
    @Inject(method = "getFurthestSchematicWorldTraceBeforeVanilla", at = @At("RETURN"),
            cancellable = true, require = 0)
    private static void layerlist$aimFurthest(Level worldClient, Entity entity, double maxRange,
            CallbackInfoReturnable<RayTraceUtils.RayTraceWrapper> cir)
    {
        if (! EasyPlaceModule.isActive() || ! AutoPlaceState.inHandler())
        {
            return;
        }

        RayTraceUtils.RayTraceWrapper current = cir.getReturnValue();

        if (current != null && ! layerlist$deadEnd(current))
        {
            return;
        }

        RayTraceUtils.RayTraceWrapper wrapper = layerlist$aim(worldClient, entity, maxRange, true);

        if (wrapper != null)
        {
            cir.setReturnValue(wrapper);
        }
    }

    // Явно трёхаргументная перегрузка: так и было (без дескриптора Mixin берёт первую), теперь — видно.
    // Её зовут наши проверки (StateRepair.target); Easy Place зовёт другую — см. aimGenericPlants ниже.
    @Inject(method = "getGenericTrace(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;D)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void layerlist$aimGeneric(CallbackInfoReturnable<RayTraceUtils.RayTraceWrapper> cir)
    {
        if (! EasyPlaceModule.isActive() || ! AutoPlaceState.inHandler())
        {
            return;
        }

        RayTraceUtils.RayTraceWrapper current = cir.getReturnValue();

        if (current != null && ! layerlist$deadEnd(current))
        {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.getCameraEntity();

        if (mc.level == null || entity == null || mc.player == null)
        {
            return;
        }

        RayTraceUtils.RayTraceWrapper wrapper = layerlist$aim(mc.level, entity,
                EasyPlaceUtils.getValidBlockRange(mc), false);

        if (wrapper != null)
        {
            com.aell.layerlist.easyplace.EpDebug.say("прицел → "
                    + com.aell.layerlist.easyplace.EpDebug.at(wrapper.getBlockHitResult().getBlockPos()));
            cir.setReturnValue(wrapper);
        }
        else
        {
            com.aell.layerlist.easyplace.EpDebug.say(current == null
                    ? "прицел: мимо, на луче нет работы"
                    : "прицел: наведённой клетке клик не нужен, замены на луче нет");
        }
    }

    /**
     * Перегрузка, которую зовёт сам Easy Place при «Easy Place First» ({@code getGenericTrace}
     * с тремя флагами, во всех Litematica от 0.28.4 до 0.29.1). Инжект выше без дескриптора
     * встаёт только в первую, трёхаргументную перегрузку — её зовут лишь наши собственные
     * проверки, поэтому в живой установке помощь прицелу не работала (найдено 01.10.2026
     * по выгрузке Mixin, см. docs/MODULES.md, модуль «Прицел»).
     *
     * <p>Здесь — только для лоз (0.0.23): тонкую голограмму лозы почти не поймать взглядом, а
     * нижние пустые клетки колонны без опоры — тупик. Для остальных блоков поведение этой
     * перегрузки сознательно оставлено ванильным: общий прицел однажды уже ломал стройку
     * (откат 22.09.2026), включать его целиком — отдельной проверкой.
     *
     * <p>И для невидимого блока света (24.10.2026): у {@code minecraft:light} контур есть, только пока
     * предмет «Свет» в руке, а берёт его в руку сам Easy Place — уже после прицела. Луч Litematica
     * проходит сквозь голограмму, клика не бывает вовсе. Такую клетку ищем и тогда, когда луч упёрся
     * в клетку схемы, которой делать нечего (свет перед готовой стеной).
     */
    @Inject(method = "getGenericTrace(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;DZZZ)Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void layerlist$aimGenericPlants(Level level, Entity entity, double maxRange, boolean respectRange,
            boolean targetFluids, boolean flag, CallbackInfoReturnable<RayTraceUtils.RayTraceWrapper> cir)
    {
        if (! EasyPlaceModule.isActive() || ! AutoPlaceState.inHandler())
        {
            return;
        }

        RayTraceUtils.RayTraceWrapper current = cir.getReturnValue();
        boolean schematicHit = current != null
                && current.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK;
        boolean deadEnd = schematicHit && layerlist$deadEnd(current);

        // Наведена клетка схемы с работой — ничего не меняем
        if (schematicHit && ! deadEnd)
        {
            return;
        }

        // Лоза — как раньше: только мимо схемы или вместо лозы-тупика
        boolean plants = ! schematicHit || layerlist$plantCell(current);
        RayTraceUtils.RayTraceWrapper wrapper = layerlist$aim(level, entity, maxRange, false);

        if (wrapper != null && plants && layerlist$plantCell(wrapper))
        {
            com.aell.layerlist.easyplace.EpDebug.say("прицел (лоза) → "
                    + com.aell.layerlist.easyplace.EpDebug.at(wrapper.getBlockHitResult().getBlockPos()));
            cir.setReturnValue(wrapper);
        }
        else if (wrapper != null && layerlist$lightCell(wrapper))
        {
            com.aell.layerlist.easyplace.EpDebug.say("прицел (свет) → "
                    + com.aell.layerlist.easyplace.EpDebug.at(wrapper.getBlockHitResult().getBlockPos()));
            cir.setReturnValue(wrapper);
        }
        else if (wrapper != null && layerlist$fireCell(wrapper))
        {
            com.aell.layerlist.easyplace.EpDebug.say("прицел (огонь) → "
                    + com.aell.layerlist.easyplace.EpDebug.at(wrapper.getBlockHitResult().getBlockPos()));
            cir.setReturnValue(wrapper);
        }
    }

    /** Наведена клетка схемы с огнём: контур у него — пластинка у самого пола, луч проходит над ней. */
    private static boolean layerlist$fireCell(RayTraceUtils.RayTraceWrapper wrapper)
    {
        BlockHitResult hit = wrapper.getBlockHitResult();
        net.minecraft.world.level.Level schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        return hit != null && schematic != null
                && com.aell.layerlist.easyplace.FirePlace.is(schematic.getBlockState(hit.getBlockPos()));
    }

    /** Наведена клетка схемы с невидимым блоком света: без предмета в руке у него нет контура. */
    private static boolean layerlist$lightCell(RayTraceUtils.RayTraceWrapper wrapper)
    {
        BlockHitResult hit = wrapper.getBlockHitResult();
        net.minecraft.world.level.Level schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        return hit != null && schematic != null
                && schematic.getBlockState(hit.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.LightBlock;
    }

    /** Наведена клетка схемы, где по схеме стоит лоза (растение, которое строится вдоль опоры). */
    private static boolean layerlist$plantCell(RayTraceUtils.RayTraceWrapper wrapper)
    {
        BlockHitResult hit = wrapper.getBlockHitResult();
        net.minecraft.world.level.Level schematic = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();

        return hit != null && schematic != null
                && com.aell.layerlist.easyplace.GrowingPlants.is(schematic.getBlockState(hit.getBlockPos()));
    }

    /** Наведённая клетка, с которой Easy Place всё равно ничего не сделает. */
    private static boolean layerlist$deadEnd(RayTraceUtils.RayTraceWrapper wrapper)
    {
        try
        {
            if (wrapper.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK)
            {
                return true;
            }

            BlockHitResult hit = wrapper.getBlockHitResult();

            if (hit == null)
            {
                return true;
            }

            // Полка с предметами или поза статуи: состояния могут совпадать, а клик
            // всё равно нужен — такую клетку прицел не покидает
            return ! SchematicTarget.actionable(hit.getBlockPos())
                    && ! com.aell.layerlist.easyplace.StateRepair.needsFix(hit.getBlockPos());
        }
        catch (RuntimeException | LinkageError e)
        {
            return false;
        }
    }

    private static @Nullable RayTraceUtils.RayTraceWrapper layerlist$aim(Level level, Entity entity,
            double maxRange, boolean furthest)
    {
        BlockHitResult hit;

        try
        {
            hit = AirTarget.find(level, entity, maxRange, furthest);
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }

        return hit == null
                ? null
                : new RayTraceUtils.RayTraceWrapper(
                        RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK, hit);
    }
}
