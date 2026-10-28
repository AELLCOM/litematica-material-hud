package com.aell.layerlist.easyplace;

import com.aell.layerlist.config.LayerListConfigs;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import fi.dy.masa.malilib.util.InventoryUtils;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public final class PlacementGuard
{
    public sealed interface Decision
    {
        Decision PASS = new Pass();
        Decision CANCEL = new Cancel();

        record Pass() implements Decision {}

        record Cancel() implements Decision {}

        record Click(@Nullable BlockHitResult hit, @Nullable LookSpoof.Pose pose,
                @Nullable BlockPos markPos, @Nullable BlockState markState) implements Decision
        {
            public boolean rewrites()
            {
                return this.hit != null || this.pose != null;
            }
        }

        record Fix(BlockPos pos, BlockState schematic, BlockState world, Direction face)
                implements Decision {}

        record Water(BlockPos pos, LookSpoof.Pose pose, BlockState world) implements Decision {}
    }

    private PlacementGuard() {}

    public static Decision decide(LocalPlayer player, InteractionHand hand, BlockHitResult rawHit)
    {
        if (! EasyPlaceModule.isActive() || AutoPlaceState.isOwnClick())
        {
            return Decision.PASS;
        }

        // Ручной клик по сундуку: «Только по схеме» отменило бы его — клетка перед сундуком
        // в схеме пуста, а блок в руке есть
        if (ContainerClick.shouldPass(rawHit))
        {
            EpDebug.say("клик по контейнеру проходит как есть");

            return Decision.PASS;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        if (level == null || ! AutoPlaceState.isAutomated(LayerListConfigs.EP_AUTOMATION_WINDOW.getIntegerValue()))
        {
            return Decision.PASS;
        }

        // Предмет только что переложен в руку (обмен Litematica, автопополнение…), а сервер ещё не ответил:
        // клик сейчас ушёл бы с рукой, которую сервер видит иначе, — так вставал не тот блок
        if (InventorySync.placeTooSoon())
        {
            ++InventorySync.placeWaits;
            EpDebug.say("клик ждёт: сервер ещё не подтвердил перекладку предметов (" + InventorySync.settleMs() + " мс)");
            return Decision.CANCEL;
        }

        WorldSchematic schematicWorld = schematicWorld();

        if (schematicWorld == null)
        {
            return Decision.PASS;
        }

        ItemStack stack = player.getItemInHand(hand);

        if (stack.isEmpty())
        {
            return Decision.PASS;
        }

        if (rawHit.getClass() != BlockHitResult.class)
        {
            return decideForeign(level, schematicWorld, stack, hand, rawHit);
        }

        // «Считать сервер ванильным»: точка попадания, закодированная протоколом Easy Place
        // (смещения на +2 за свойство уводят её далеко за пределы блока), — сигнатура, по
        // которой Litematica узнают античиты. Возвращаем точку на грань, по лучу взгляда.
        BlockHitResult clean = PlainHits.sanitize(player, rawHit);
        BlockHitResult hit = clean != null ? clean : rawHit;

        Decision decision = decideBlock(level, schematicWorld, player, hand, stack, hit);

        // Полка, сундук, воронка и прочее, что «съедает» правый клик: без приседа наш клик
        // не поставит блок, а наполнит полку или откроет экран. Присед навешиваем В КОНЦЕ,
        // поверх позы решателя, — раньше этим занимался protectShelf, но он перехватывал
        // клик ДО решателя и отдавал сырой угол игрока, отчего полки вставали чужим боком
        // и лезли друг на друга.
        decision = sneakIfEaten(level, player, stack, hit, clean, decision);

        // Клик прошёл бы как есть, но точку попадания мы чистили — подменяем её и при PASS
        if (decision instanceof Decision.Pass && clean != null)
        {
            return new Decision.Click(clean, null, null, null);
        }

        return decision;
    }

    /** Шалкер с предметами внутри — переносной склад игрока. */
    static boolean filledShulker(ItemStack stack)
    {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock
                && InventoryUtils.shulkerBoxHasItems(stack);
    }

    private static Decision decideBlock(ClientLevel level, WorldSchematic schematicWorld,
            LocalPlayer player, InteractionHand hand, ItemStack stack, BlockHitResult hit)
    {
        Decision snow = snowFromAnySide(level, schematicWorld, player, stack, hit);

        if (snow != null)
        {
            return snow;
        }

        BlockPos target = new BlockPlaceContext(player, hand, stack, hit).getClickedPos();
        BlockState schematic = schematicWorld.getBlockState(target);

        // Заполненный шалкер — это склад игрока, который он принёс к стройке, а не блок
        // схемы: его ставят рядом, чтобы брать материалы. «Только по схеме» и остальные
        // отказы его не касаются — ручной клик проходит как есть, где бы игрок ни стоял.
        // Только ручной: автоклик Litematica с шалкером в руке значит, что схема просит
        // именно шалкер, и туда полный ставить нельзя.
        if (! AutoPlaceState.ourClick() && filledShulker(stack))
        {
            EpDebug.say("клик " + EpDebug.at(target) + ": заполненный шалкер, ставим без проверок");
            return Decision.PASS;
        }

        if (EpDebug.on() && ! schematic.isAir())
        {
            EpDebug.say("клик " + EpDebug.at(target) + ": схема " + EpDebug.name(schematic)
                    + ", мир " + EpDebug.name(level.getBlockState(target))
                    + ", в руке " + stack.getItem()
                    + (hit.getBlockPos().equals(target) ? "" : ", бьём по " + EpDebug.at(hit.getBlockPos()))
                    + " грань " + hit.getDirection()
                    + String.format(java.util.Locale.ROOT, " (%.2f %.2f %.2f)", hit.getLocation().x - hit.getBlockPos().getX(),
                            hit.getLocation().y - hit.getBlockPos().getY(), hit.getLocation().z - hit.getBlockPos().getZ()));
        }

        // Клетка, по которой бьём, могла быть поставлена нами же тиком раньше. Пока сервер
        // её не подтвердил, он разбирает клик по своему миру: опоры там ещё нет, луч уходит
        // на другую грань, и блок ложится не туда и чужой осью. Ветка решателя проверяет
        // свою опору отдельно, но простые блоки без задаваемых свойств (гладкий кварц,
        // камень, доски) идут мимо решателя — этот гейт закрывает и их. Только свои клики:
        // ручной клик игрока мы не трогаем.
        if (AutoPlaceState.ourClick())
        {
            // Сама целевая клетка: Litematica на легаси-пути кликает именно по ней, поэтому
            // повторный клик до ответа сервера — самый частый случай. На сервере клетка уже
            // занята нашим же блоком, и ванильный BlockPlaceContext уводит установку на
            // клетку за гранью: так вырастают лишние блоки сверху и сбоку.
            if (AutoPlaceState.isConfirmationPending(target, level.getBlockState(target),
                    LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
            {
                EpDebug.say("отмена " + EpDebug.at(target)
                        + ": прошлая установка сюда ещё не подтверждена сервером");

                return Decision.CANCEL;
            }

            // Опора, по которой бьём, если это другая клетка: её мы тоже могли поставить
            // тиком раньше, и тогда сервер разберёт клик по своему миру, где её ещё нет.
            if (! hit.getBlockPos().equals(target)
                    && AutoPlaceState.isConfirmationPending(hit.getBlockPos(),
                            level.getBlockState(hit.getBlockPos()),
                            LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
            {
                EpDebug.say("отмена " + EpDebug.at(target) + ": опора "
                        + EpDebug.at(hit.getBlockPos()) + " ещё не подтверждена сервером");

                return Decision.CANCEL;
            }
        }

        if (schematic.isAir())
        {
            // Клик Easy Place, съехавший с занятой клетки. Когда кликнутая клетка занята,
            // ванильный BlockPlaceContext уводит установку на клетку за гранью клика —
            // так над только что поставленным забором вырастает лишний. Собственная защита
            // Litematica здесь не срабатывает: её `canPlaceBlock` спрашивает у malilib
            // `PlacementUtils.isReplaceable`, а тот отвечает «да» для всего, что просто
            // может стоять в этой клетке, — забор в том числе.
            //
            // Схема в клетке пуста: такой блок лишний всегда, и за границей области
            // размещения тоже. Верхний ряд забора — как раз она, поэтому проверка ниже
            // («Только по схеме» + внутри области) этот случай не ловила.
            // Отменяем только СВОЙ клик — тот, что идёт из обработчика Litematica. Окна
            // isAutomated для этого мало: оно истинно ещё секунду после установки, а клавиша
            // активации по умолчанию — правая кнопка мыши, так что под отмену попали бы
            // обычные ручные установки (леса, факелы, лестницы).
            if (AutoPlaceState.ourClick() && stack.getItem() instanceof BlockItem
                    && ! target.equals(hit.getBlockPos()))
            {
                EpDebug.say("отмена " + EpDebug.at(target) + ": клик по занятой клетке "
                        + EpDebug.at(hit.getBlockPos()) + " съехал бы за грань "
                        + hit.getDirection() + ", схема здесь пуста");

                return Decision.CANCEL;
            }

            // «Только по схеме»: пустая в схеме клетка внутри области размещения — лишний
            // блок. Так появляется забор «рядом»: Litematica промахнулась, вернула PASS,
            // и обычный правый клик по грани уже стоящего забора ставит соседний.
            if (AutoPlaceState.ourClick() && LayerListConfigs.EP_ONLY_SCHEMATIC.getBooleanValue()
                    && stack.getItem() instanceof BlockItem
                    && SchematicTarget.insideSchematic(target))
            {
                EpDebug.say("отмена " + EpDebug.at(target) + ": лишний блок — схема здесь пуста");
                return Decision.CANCEL;
            }

            return Decision.PASS;
        }

        // «Только по схеме»: в руке блок, который в этой клетке не даст блок схемы
        if (LayerListConfigs.EP_ONLY_SCHEMATIC.getBooleanValue()
                && ! expectedItem(stack, schematic))
        {
            EpDebug.say("отмена " + EpDebug.at(target) + ": предмет " + stack.getItem()
                    + " не для клетки " + EpDebug.name(schematic));
            return Decision.CANCEL;
        }

        // Сервер уже отказал по этой клетке столько раз, что дальше кликать бессмысленно:
        // честный отказ вместо повтора каждые две секунды до конца стройки. Ручной клик
        // не держим: игрок ставит сам — значит, знает, зачем.
        if (AutoPlaceState.ourClick() && AutoPlaceState.refused(target))
        {
            EpDebug.say("отмена " + EpDebug.at(target) + ": сервер отказал слишком много раз");
            return Decision.CANCEL;
        }

        // Голова лозы там, где схема просит тело, — клетка уже своя: тело получится само,
        // когда встанет следующая. Клик сюда бесполезен, работа — дальше по колонне.
        // Земля там, где схема просит дёрн, и тумблер разрешает: клетка готова, дёрн нарастёт
        // сам. Без этой ветки мод возвращался бы к ней вечно и ронял лишний блок за гранью.
        if (GrassSwap.satisfies(schematic, level.getBlockState(target)))
        {
            EpDebug.say("отмена " + EpDebug.at(target) + ": земля вместо дёрна, зарастёт сама");

            return Decision.CANCEL;
        }

        if (GrowingPlants.satisfies(schematic, level.getBlockState(target)))
        {
            EpDebug.say("отмена " + EpDebug.at(target) + ": колонна дорастёт сама");
            return Decision.CANCEL;
        }

        // Клетка-приёмник уже держит то, что просит схема, либо отличается только тем, чего
        // установкой не задать. Ставить тут нечего: клик сюда — либо дубликат, либо съехавший
        // с занятой клетки, а ванильный BlockPlaceContext уводит такой клик на клетку за
        // гранью — так над стеной и вырастал лишний блок. Страховка поверх SchematicTarget:
        // там мы перестаём считать такую клетку работой, здесь — не пускаем сам клик, откуда
        // бы он ни пришёл. Клики игрока не трогаем, только автоматические.
        if (stack.getItem() instanceof BlockItem)
        {
            BlockState landing = level.getBlockState(target);

            if (landing == schematic || Stairs.sameShape(schematic, landing)
                    || Controllable.placementCannotChange(schematic, landing))
            {
                EpDebug.say("отмена " + EpDebug.at(target) + ": в клетке уже "
                        + EpDebug.name(landing) + ", установкой её не поправить");
                return Decision.CANCEL;
            }
        }

        // Темп: пока не вышла «Задержка между блоками», очередной клик по клетке схемы
        // не уходит. Клики мимо схемы — сундук, дверь, рычаг — считаются ручными и идут
        // как обычно, их темп не ограничивается ничем.
        if (Pacing.tooSoon())
        {
            EpDebug.say("отмена " + EpDebug.at(target) + ": темп («Блоков в секунду»)");
            return Decision.CANCEL;
        }

        Pacing.mark();

        BlockState world = level.getBlockState(target);

        // Огонь: огнивом по опоре рядом — своего предмета у огня нет
        Decision fire = FirePlace.decide(level, player, stack, target, schematic, world);

        if (fire != null)
        {
            return fire;
        }

        Decision water = decideOnWater(level, player, stack, target, schematic, world);

        if (water != null)
        {
            return water;
        }

        // Дверь и люк встают закрытыми, свеча — незажжённой: такую клетку дожмём сами
        if (world.canBeReplaced() && StateFix.mayFollowUp(schematic))
        {
            FollowUp.schedule(target);
        }

        Decision data = guardItemData(player, hand, stack, schematicWorld, target, schematic, world);

        if (data != null)
        {
            return data;
        }

        Decision stripped = decideStripping(target, schematic, world);

        if (stripped != null)
        {
            return stripped;
        }

        Decision plant = decidePlant(level, player, stack, target, schematic, world);

        if (plant != null)
        {
            return plant;
        }

        if (world.is(schematic.getBlock()))
        {
            return decideSameBlock(player, hand, stack, level, schematicWorld, target, schematic, world, hit);
        }

        if (BlockFamilies.isMultiface(schematic))
        {
            return decideMultiface(level, target, schematic, world);
        }

        Decision counted = decideCount(target, schematic, world);

        if (counted != null)
        {
            return counted;
        }

        Decision solved = solve(level, schematicWorld, player, hand, stack, target, schematic, hit);

        if (solved != null)
        {
            EpDebug.say("решатель " + EpDebug.at(target) + ": "
                    + (solved instanceof Decision.Cancel ? "не встанет верно — пропуск" : "клик переписан"));
            return solved;
        }

        Decision attached = decideFaceAttached(stack, schematic, world, hit);

        if (attached instanceof Decision.Pass)
        {
            EpDebug.say("клик " + EpDebug.at(target) + " проходит как есть");
        }

        return attached;
    }

    /**
     * Клик, который блок «съест» (полка наполнится предметом, сундук откроется), превращаем
     * в клик с приседом — тогда ваниль ставит блок. Угол берём тот, что посчитал решатель,
     * и только добавляем присед; своего угла не выдумываем.
     */
    private static Decision sneakIfEaten(ClientLevel level, LocalPlayer player, ItemStack stack,
            BlockHitResult raw, @Nullable BlockHitResult clean, Decision decision)
    {
        if (player.isShiftKeyDown() || ! (stack.getItem() instanceof BlockItem))
        {
            return decision;
        }

        if (decision instanceof Decision.Cancel || decision instanceof Decision.Fix
                || decision instanceof Decision.Water)
        {
            return decision;
        }

        // Клик без своей точки попадания — это «пройти как есть, только отметить клетку»
        // (счётные семьи: свечи, свечные торты). Присед превратил бы его в установку с
        // приседом, а ваниль тогда кладёт свечу не в стопку, а на клетку за гранью.
        if (decision instanceof Decision.Click marked && marked.hit() == null)
        {
            return decision;
        }

        BlockHitResult hit = decision instanceof Decision.Click click && click.hit() != null
                ? click.hit()
                : (clean != null ? clean : raw);
        BlockPos clicked = hit.getBlockPos();

        if (! Interactive.eatsRightClick(level, clicked, level.getBlockState(clicked)))
        {
            return decision;
        }

        if (decision instanceof Decision.Click click)
        {
            LookSpoof.Pose pose = click.pose() == null ? LookSpoof.Pose.of(player) : click.pose();

            return new Decision.Click(hit, new LookSpoof.Pose(pose.yaw(), pose.pitch(), true),
                    click.markPos(), click.markState());
        }

        return new Decision.Click(hit, new LookSpoof.Pose(player.getYRot(), player.getXRot(), true),
                null, null);
    }

    // Подкласс BlockHitResult — «инженерный» клик другого мода (EasyPlaceFix шлёт
    // RelativeBlockHitResult и на время клика переписывает исходящие look-пакеты на свои
    // yaw/pitch, так что наш LookSpoof до сервера не доходит; переписывание такого клика
    // ломает и его расчёт, и наш). Если блок ляжет в клетку, которую схема просит заполнить, —
    // не вмешиваемся. Если же клик пришёлся в заменяемую клетку, где схема пуста (расчёт чужого
    // мода опёрся на ещё не построенный блок — потолочное точило без опоры), блок лёг бы мимо
    // цели: клик гасим, а когда схема просит этот блок в соседней клетке за гранью клика,
    // ставим его сами тиком позже, когда чужая блокировка взгляда уже снята.
    private static Decision decideForeign(ClientLevel level, WorldSchematic schematicWorld,
            ItemStack stack, InteractionHand hand, BlockHitResult hit)
    {
        if (! (stack.getItem() instanceof BlockItem))
        {
            return Decision.PASS;
        }

        BlockPos clicked = hit.getBlockPos();

        if (! level.getBlockState(clicked).canBeReplaced()
                || ! schematicWorld.getBlockState(clicked).isAir())
        {
            return Decision.PASS;
        }

        BlockPos intended = clicked.relative(hit.getDirection());
        BlockState wanted = schematicWorld.getBlockState(intended);

        if (! wanted.isAir() && wanted.getBlock().asItem() == stack.getItem())
        {
            DeferredPlace.schedule(intended, hand);
        }

        return Decision.CANCEL;
    }

    // Баннеры, горшки и головы: узор, черепки и профиль живут в компонентах, а не в
    // состоянии блока, поэтому у трёх баннеров одного цвета состояние одинаковое и
    // Litematica берёт первый подходящий предмет — узор выходит случайный. Берём в
    // руку именно то, что просит схема; если такого предмета нет, позицию лучше
    // пропустить, чем поставить чужой узор (по тумблеру «Пропускать то, что встанет
    // неверно»).
    private static @Nullable Decision guardItemData(LocalPlayer player, InteractionHand hand,
            ItemStack stack, WorldSchematic schematicWorld, BlockPos target, BlockState schematic,
            BlockState world)
    {
        if (! ItemData.matters(schematic) || world.is(schematic.getBlock()))
        {
            return null;
        }

        ItemStack required = ItemData.requiredIfMatters(schematicWorld, target, schematic);

        if (required == null || ItemData.same(stack, required))
        {
            return null;
        }

        // Смена слота меняет только главную руку — для второй руки остаётся проверка
        if (hand == InteractionHand.MAIN_HAND && ItemData.takeInHand(player, required))
        {
            return null;
        }

        // Предмета с нужными данными у игрока нет. Отменять молча нельзя: до того как мод
        // стал требовать предмет с узором, на этом месте вставал баннер без узора, и
        // пропажа выглядит поломкой мода.
        ItemData.reportIfMissing(target, required);

        return LayerListConfigs.EP_SKIP_WRONG.getBooleanValue() ? Decision.CANCEL : null;
    }

    // Схема просит обтёсанное бревно, а в клетке уже стоит его необтёсанный аналог:
    // ставить нечего, надо пройтись топором — это делает StateFixer.
    private static @Nullable Decision decideStripping(BlockPos target, BlockState schematic,
            BlockState world)
    {
        if (! Stripping.needsStrip(schematic, world))
        {
            return null;
        }

        return AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue())
                ? Decision.CANCEL
                : new Decision.Fix(target, schematic, world, Direction.UP);
    }

    // Светящиеся ягоды, лозы, ламинария, свисающий мох: сам клик проходит нетронутым —
    // так кликают и ванильная Litematica, и EasyPlaceFix: по клетке цели, без приседа
    // и без подмены точки (клетка пуста, действия блока в ней нет — снимать ягоды не с
    // чего). У «тела» лозы своего предмета нет: ставится голова, а верхняя клетка
    // превращается в тело сама, когда под ней появляется следующая. Наша работа здесь —
    // только страховки: не ставить без опоры и не удлинять колонну вторым кликом.
    private static @Nullable Decision decidePlant(ClientLevel level, LocalPlayer player,
            ItemStack stack, BlockPos target, BlockState schematic, BlockState world)
    {
        if (! GrowingPlants.is(schematic))
        {
            return null;
        }

        if (! world.canBeReplaced())
        {
            EpDebug.say("лоза " + EpDebug.at(target) + ": клетка занята (" + EpDebug.name(world) + ")");
            return null;
        }

        if (GrowingPlants.item(schematic) != stack.getItem())
        {
            EpDebug.say("лоза " + EpDebug.at(target) + ": в руке " + stack.getItem()
                    + ", а колонна ставится предметом " + GrowingPlants.item(schematic));
            return null;
        }

        if (! GrowingPlants.placed(schematic).canSurvive(level, target))
        {
            // Опора ещё не построена — вернёмся к клетке, когда она появится
            EpDebug.say("лоза " + EpDebug.at(target) + ": нет опоры — жду");
            return LayerListConfigs.EP_SKIP_WRONG.getBooleanValue() ? Decision.CANCEL : null;
        }

        // Второй клик до ответа сервера удлинил бы лозу на клетку дальше схемы
        if (AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            EpDebug.say("лоза " + EpDebug.at(target) + ": жду подтверждения сервером");
            return Decision.CANCEL;
        }

        EpDebug.say("лоза " + EpDebug.at(target) + ": клик проходит как есть");
        return markOnly(target, world);
    }

    // Кувшинка и икра лягушки: клик по клетке для них пустой в принципе — их предмет
    // отдаёт useOn как PASS и работает только через use(), где сервер сам трассирует
    // взгляд игрока по воде. Поэтому клик здесь не переписывается, а заменяется на use()
    // со взглядом, наведённым в нужную клетку воды (см. {@link WaterPlace}).
    private static @Nullable Decision decideOnWater(ClientLevel level, LocalPlayer player,
            ItemStack stack, BlockPos target, BlockState schematic, BlockState world)
    {
        if (! WaterPlace.is(stack) || ! WaterPlace.matches(stack, schematic)
                || world.is(schematic.getBlock()))
        {
            return null;
        }

        if (! world.canBeReplaced())
        {
            EpDebug.say("вода " + EpDebug.at(target) + ": клетка занята ("
                    + EpDebug.name(world) + ")");
            return Decision.CANCEL;
        }

        if (! WaterPlace.supported(level, target, schematic))
        {
            // Вода под клеткой ещё не налита — вернёмся, когда она появится
            EpDebug.say("вода " + EpDebug.at(target) + ": опоры из воды пока нет — жду");
            return Decision.CANCEL;
        }

        if (AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        LookSpoof.Pose pose = WaterPlace.aim(level, player, target);

        if (pose == null)
        {
            EpDebug.say("вода " + EpDebug.at(target) + ": лучом до этой клетки воды не дотянуться");
            return Decision.CANCEL;
        }

        return new Decision.Water(target, pose, world);
    }

    // «Только по схеме»: какие предметы имеют право кликать в клетку с этим блоком схемы.
    // Не-блоки (огниво, ведро, топор, око) — всегда: их фильтруют собственные починки.
    static boolean expectedItem(ItemStack stack, BlockState schematic)
    {
        if (! (stack.getItem() instanceof BlockItem item))
        {
            return true;
        }

        if (item.getBlock() == schematic.getBlock()
                || schematic.getBlock().asItem() == stack.getItem())
        {
            return true;
        }

        // Горшок с растением: сначала пустой горшок, потом само растение
        if (Potting.plant(schematic) != null
                && (item.getBlock() == net.minecraft.world.level.block.Blocks.FLOWER_POT
                        || Potting.plant(schematic) == stack.getItem()))
        {
            return true;
        }

        // Дёрн строится землёй, пока включён тумблер «Ставить землю вместо дёрна»:
        // без этой ветки «Только по схеме» отменяло бы клик с землёй в руке, и подмена
        // требуемого предмета в MaterialCache не доводила бы установку до конца.
        if (GrassSwap.wants(schematic)
                && item.getBlock() == net.minecraft.world.level.block.Blocks.DIRT)
        {
            return true;
        }

        // Обтёсанное бревно строится необтёсанным аналогом
        if (Stripping.plainFor(schematic) == item.getBlock())
        {
            return true;
        }

        // Свечной торт строится тортом, грядка и тропинка — землёй
        if (Conversions.allowedItem(schematic, item.getBlock()))
        {
            return true;
        }

        // Клетку «тела» лозы ставит предмет головы. Если предмет колонны выяснить не
        // удалось (accessor не сработал) — страховка отпускает клик, а не съедает его.
        if (GrowingPlants.is(schematic))
        {
            net.minecraft.world.item.Item plantItem = GrowingPlants.item(schematic);

            return plantItem == null || plantItem == stack.getItem();
        }

        return false;
    }

    /**
     * Тот же блок, но повёрнут не так, как просит схема: установкой это не правится
     * (свойство задаёт угол взгляда при установке, а клетка уже занята), интеракцией —
     * тоже. Правый клик по занятой клетке ванильный {@code BlockPlaceContext} уводит на
     * клетку ЗА ГРАНЬЮ — оттуда и берутся лишние блоки «поверх»: камнерез, полка, печь,
     * бревно осью не туда. Пропускаем клетку и говорим, что исправить её можно только
     * сломав стоящий блок.
     *
     * Клетка после этого перестаёт быть работой и для прицела — см.
     * {@code SchematicTarget.actionable}: иначе прицел залипал бы на ней и стройка вставала.
     */
    private static @Nullable Decision skipWrongRotation(ItemStack stack, BlockPos target,
            BlockState schematic, BlockState world)
    {
        if (world.canBeReplaced() || ! (stack.getItem() instanceof BlockItem)
                || ! Controllable.placementOnlyDiffers(schematic, world))
        {
            return null;
        }

        // Плита, которой не хватает второй половины: кликом тем же предметом она достраивается,
        // ломать ничего не надо — как в 0.0.20. (Общий предикат для прицела снят до проверки.)
        if (schematic.hasProperty(BlockStateProperties.SLAB_TYPE)
                && schematic.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE)
        {
            return null;
        }

        String why = "стоит " + EpDebug.name(world) + ", схема просит "
                + EpDebug.name(schematic) + " — кликом не повернуть, нужно сломать";

        // Тумблер «Пропускать то, что встанет неверно» выключен — значит игрок просил не
        // отменять за него: говорим и пропускаем клик как есть, как это делают решатель
        // и decideFaceAttached.
        if (! LayerListConfigs.EP_SKIP_WRONG.getBooleanValue())
        {
            EpDebug.say("клик " + EpDebug.at(target) + " проходит как есть: " + why);

            return null;
        }

        AutoPlaceState.countSkip(target, why);

        return Decision.CANCEL;
    }

    private static Decision decideSameBlock(LocalPlayer player, InteractionHand hand, ItemStack stack,
            ClientLevel level, WorldSchematic schematicWorld, BlockPos target, BlockState schematic,
            BlockState world, BlockHitResult hit)
    {
        // Угол ступени другой записью — та же фигура (Stairs.twin), клетка готова
        if (Stairs.sameShape(schematic, world))
        {
            return Decision.CANCEL;
        }

        // Одиночный сундук на месте половины пары: клик здесь бесполезен, `type` задаст
        // установка второй половины — ванильный ChestBlock.updateShape слепит обе.
        if (DoubleChest.awaitingPartner(schematic, world))
        {
            EpDebug.say("сундук " + EpDebug.at(target) + ": ждёт вторую половину пары");
            return Decision.CANCEL;
        }

        if (BlockFamilies.isMultiface(schematic) && CountedProgress.stillGrowing(target))
        {
            return decideMultiface(level, target, schematic, world);
        }

        if (stillCounting(schematic, world))
        {
            Decision counted = decideCount(target, schematic, world);

            if (counted != null)
            {
                return counted;
            }
        }

        Decision doubled = decideDoubleSlab(player, hand, stack, level, target, schematic, world);

        if (doubled != null)
        {
            return doubled;
        }

        if (StateFix.plan(level, schematicWorld, target, schematic, world) == null)
        {
            // Рельс уже стоит, разошлась только форма: кликом её не поправить (форму
            // задают соседи), а стоящий рельс не заменяется — правый клик по нему ушёл
            // бы в клетку над ним и положил бы лишний рельс поверх пути
            if (Rails.onlyShapeDiffers(schematic, world))
            {
                EpDebug.say("рельс " + EpDebug.at(target) + ": стоит формой "
                        + EpDebug.name(world) + ", схема просит " + EpDebug.name(schematic)
                        + " — кликом не исправить");

                return Decision.CANCEL;
            }

            Decision turned = skipWrongRotation(stack, target, schematic, world);

            if (turned != null)
            {
                return turned;
            }

            return Decision.PASS;
        }

        // План доводки есть, но поворот он не правит: повернуть стоящий блок интеракцией
        // нельзя ни одним планом. Без этой ветки клетка уходила бы в доводку, доводка не
        // помогала, и клик повторялся бы по занятой клетке — а оттуда лишний блок за гранью.
        Decision turnedWithPlan = skipWrongRotation(stack, target, schematic, world);

        if (turnedWithPlan != null)
        {
            return turnedWithPlan;
        }

        if (AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        return new Decision.Fix(target, schematic, world, hit.getDirection());
    }

    /**
     * Удвоённая плита ({@code type=double}) одним кликом не получается никогда: нужен
     * второй клик тем же предметом по уже стоящей половине. Доводка есть в самой
     * Litematica, но её ветка исполняется только при неудаче клика — на успешной установке
     * управление уходит на выход раньше (проверено байткодом 0.28.4), поэтому все 68
     * плиточных блоков молча оставались одинарными.
     *
     * Точка клика берётся на настоящей поверхности плиты, а не на грани клетки: у нижней
     * плиты «верх» клетки висел бы в воздухе над блоком, и такой клик античит отменяет.
     * Результат проверяется ванильной симуляцией — {@code PlaceSolver.attempt} считает
     * {@code placementState} и сверяет, что блок ляжет именно в эту клетку.
     */
    private static @Nullable Decision decideDoubleSlab(LocalPlayer player, InteractionHand hand,
            ItemStack stack, ClientLevel level, BlockPos target, BlockState schematic, BlockState world)
    {
        if (! schematic.hasProperty(BlockStateProperties.SLAB_TYPE)
                || ! world.hasProperty(BlockStateProperties.SLAB_TYPE)
                || schematic.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE
                || world.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                || schematic.getBlock().asItem() != stack.getItem())
        {
            return null;
        }

        // Второй клик до ответа сервера ушёл бы вхолостую — плита уже удвоена предсказанием
        if (AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        Direction face = world.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM
                ? Direction.UP
                : Direction.DOWN;
        Vec3 point = new Vec3(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
        BlockHitResult hit = new BlockHitResult(point, face, target, false);
        PlaceSolver.Plan plan = PlaceSolver.attempt(level, player, hand, stack, target, schematic, hit,
                LookSpoof.Pose.of(player));

        if (plan == null || ! plan.perfect())
        {
            return null;
        }

        EpDebug.say("плита " + EpDebug.at(target) + ": удваиваю вторым кликом гранью " + face);

        return new Decision.Click(plan.hit(), plan.pose(), target, world);
    }

    /**
     * Вторая половина пары сундуков: первый уже стоит одиночным нужным лицом. Клик —
     * ровно тот, каким пару ставит ванильный игрок: присед и боковая грань первого
     * сундука ({@link DoubleChest#mergeClick}). Лицо и {@code type} при этом берутся от
     * партнёра, а не из угла взгляда, — поэтому путь не требует ни поворота игрока, ни
     * удачи перебора и не слипнется с посторонним сундуком с другой стороны. Результат
     * всё равно сверяется симуляцией: не совпал — решаем как обычно.
     */
    private static @Nullable Decision decideChestPair(ClientLevel level, LocalPlayer player,
            InteractionHand hand, ItemStack stack, BlockPos target, BlockState schematic)
    {
        if (! DoubleChest.wantsPair(schematic) || ! DoubleChest.partnerReady(level, target, schematic))
        {
            return null;
        }

        BlockState world = level.getBlockState(target);

        // Второй клик до ответа сервера слепил бы пару с клеткой дальше по ряду
        if (AutoPlaceState.isConfirmationPending(target, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        BlockHitResult hit = DoubleChest.mergeClick(target, schematic);
        LookSpoof.Pose pose = new LookSpoof.Pose(player.getYRot(), player.getXRot(), true);
        PlaceSolver.Plan plan = PlaceSolver.attempt(level, player, hand, stack, target, schematic,
                hit, pose);

        if (plan == null || ! plan.perfect())
        {
            return null;
        }

        EpDebug.say("сундук " + EpDebug.at(target) + ": вторая половина — присед-клик по грани "
                + hit.getDirection() + " первого сундука " + EpDebug.at(hit.getBlockPos()));

        return new Decision.Click(plan.hit(), plan.pose(), target, world);
    }

    private static @Nullable Decision solve(ClientLevel level, WorldSchematic schematicWorld,
            LocalPlayer player, InteractionHand hand, ItemStack stack, BlockPos target,
            BlockState wantedState, BlockHitResult hit)
    {
        // В руке может лежать необтёсанный аналог: клик подбирается под него, обтешем потом.
        // Половина двойного сундука без уже стоящего партнёра недостижима — целью становится
        // одиночный сундук нужного лица, пару доведёт установка второй половины.
        BlockState schematic = DoubleChest.placeInstead(Stripping.placeInstead(wantedState, stack),
                level, target);

        // Угловая ступень, записанная схемой «чужой» спиной: с её facing ваниль соберёт среди
        // соседей другую фигуру, а со второй записью угла — ту, что в схеме (Stairs)
        BlockState stair = Stairs.placeInstead(schematic, level, schematicWorld, target);

        if (stair != schematic)
        {
            EpDebug.say("ступень " + EpDebug.at(target) + ": угол ставлю второй записью — "
                    + stair.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    + " " + stair.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.STAIRS_SHAPE));
            schematic = stair;
        }

        // Вторая половина пары сундуков: не доверять её перебору — клик задаётся сразу
        Decision pair = decideChestPair(level, player, hand, stack, target, schematic);

        if (pair != null)
        {
            return pair;
        }

        if (! BlockFamilies.solverHandles(schematic))
        {
            return null;
        }

        PlaceSolver.Plan direct = faceAttach(level, player, hand, stack, target, schematic);

        if (direct != null && direct.perfect())
        {
            sayPlan(target, direct);

            return new Decision.Click(direct.hit(), direct.pose(), target, level.getBlockState(target));
        }

        PlaceSolver.Plan plan = PlaceSolver.solve(level, player, hand, stack, target, schematic, hit);

        if (plan == null)
        {
            if (LayerListConfigs.EP_SKIP_WRONG.getBooleanValue() && Controllable.orientable(schematic))
            {
                // Отказ был молчаливым: ни отладки, ни сообщения. Для ориентируемых блоков
                // (у бревна это AXIS) он же и самый частый, и снаружи неотличим от «мод сломался».
                AutoPlaceState.countSkip(target, "решатель не нашёл позы под " + schematic);

                return Decision.CANCEL;
            }

            return null;
        }

        if (plan.misplaced() && LayerListConfigs.EP_SKIP_WRONG.getBooleanValue()
                && waitingHelps(level, schematicWorld, target, schematic))
        {
            AutoPlaceState.countSkip(target, "любой доступный клик поставил бы неверно, жду "
                    + plan.expected());

            return Decision.CANCEL;
        }

        if (! plan.perfect() && ! plainGeometry())
        {
            return null;
        }

        // Опора, по которой собрались бить, могла быть поставлена нами же мгновение назад:
        // на 20 блоках в секунду интервал равен одному тику, и сервер ещё не подтвердил её.
        // Клик по неподтверждённой опоре сервер разбирает по СВОЕМУ миру — там опоры нет,
        // и установка уезжает на соседнюю клетку: так растут лишние блоки сверху и сбоку.
        BlockPos anchor = plan.hit().getBlockPos();

        if (! anchor.equals(target) && AutoPlaceState.isConfirmationPending(anchor,
                level.getBlockState(anchor), LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            EpDebug.say("решатель " + EpDebug.at(target) + ": опора " + EpDebug.at(anchor)
                    + " ещё не подтверждена сервером");

            return Decision.CANCEL;
        }

        sayPlan(target, plan);

        return new Decision.Click(plan.hit(), plan.pose(), target, level.getBlockState(target));
    }

    /**
     * «Пропускать то, что встанет неверно» откладывает клетку до лучших времён — но
     * только пока эти времена вообще возможны. У рельса форму задают соседи, и пока
     * схема просит рядом рельс, которого ещё нет, ждать осмысленно: доложат соседа —
     * и клетка встанет точно (или её развернёт ванильная доводка). Когда соседство уже
     * достроено, ждать нечего: пропуск оставил бы в пути дыру навсегда, поэтому ставим
     * то, что получается. Для всех остальных блоков ответ прежний — пропускать.
     */
    private static boolean waitingHelps(ClientLevel level, WorldSchematic schematicWorld,
            BlockPos target, BlockState schematic)
    {
        if (! Rails.is(schematic))
        {
            return true;
        }

        if (Rails.mayImprove(level, schematicWorld, target))
        {
            EpDebug.say("рельс " + EpDebug.at(target) + ": форма ещё не та — жду соседей");

            return true;
        }

        EpDebug.say("рельс " + EpDebug.at(target)
                + ": соседи достроены, точной формы уже не выйдет — ставлю как есть");

        return false;
    }

    /**
     * Полный план клика в отладку: без него отказ сервера нечем разбирать — в чате
     * остаётся только счётчик «отказал N раз», а что именно уходило в пакет, не видно.
     */
    private static void sayPlan(BlockPos target, PlaceSolver.Plan plan)
    {
        if (! EpDebug.on())
        {
            return;
        }

        EpDebug.say("план " + EpDebug.at(target) + ": жду " + plan.expected()
                + "; клик по " + EpDebug.at(plan.hit().getBlockPos())
                + " грань " + plan.hit().getDirection()
                + ", взгляд " + plan.pose().yaw() + "/" + plan.pose().pitch()
                + (plan.pose().sneak() ? ", присед" : ""));
    }

    private static @Nullable PlaceSolver.Plan faceAttach(ClientLevel level, LocalPlayer player,
            InteractionHand hand, ItemStack stack, BlockPos target, BlockState schematic)
    {
        if (! FaceAttach.is(schematic) || ! level.getBlockState(target).canBeReplaced()
                || ! Pacing.mayTurn())
        {
            return null;
        }

        Direction support = FaceAttach.support(schematic);
        LookSpoof.Pose pose = FaceAttach.pose(schematic, player.isShiftKeyDown());

        if (pose == null)
        {
            return null;
        }

        BlockHitResult hit = new BlockHitResult(FaceAttach.spot(target, support),
                support.getOpposite(), target, false);

        return PlaceSolver.attempt(level, player, hand, stack, target, schematic, hit, pose);
    }

    private static boolean plainGeometry()
    {
        if (LayerListConfigs.EP_PLAIN_HITS.getBooleanValue())
        {
            return true;
        }

        try
        {
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();

            return protocol == EasyPlaceProtocol.NONE || protocol == EasyPlaceProtocol.SLAB_ONLY;
        }
        catch (RuntimeException | LinkageError e)
        {
            return true;
        }
    }

    /**
     * Снег слоем с любой стороны (запрос 09.10.2026). Ваниль добавляет слой, только если кликнуть по
     * ВЕРХНЕЙ грани снега: игрок смотрит сбоку или снизу — блок ушёл бы в соседнюю клетку (там его гасят
     * защиты). Клик по снегу, которому не хватает слоёв, переписываем на верх этой клетки — точка на
     * поверхности снега, взгляд на неё.
     */
    static boolean snowFromSide(BlockState wanted, BlockState world, ItemStack stack, Direction face)
    {
        return face != Direction.UP && stack.is(net.minecraft.world.item.Items.SNOW)
                && world.is(net.minecraft.world.level.block.Blocks.SNOW) && wanted.is(net.minecraft.world.level.block.Blocks.SNOW)
                && world.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS)
                        < wanted.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS);
    }

    private static @Nullable Decision snowFromAnySide(ClientLevel level, WorldSchematic schematicWorld, LocalPlayer player,
            ItemStack stack, BlockHitResult hit)
    {
        BlockPos pos = hit.getBlockPos();
        BlockState world = level.getBlockState(pos);

        if (! snowFromSide(schematicWorld.getBlockState(pos), world, stack, hit.getDirection()))
        {
            return null;
        }

        if (AutoPlaceState.isConfirmationPending(pos, world, LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()) || Pacing.tooSoon())
        {
            return Decision.CANCEL;
        }

        Pacing.mark();
        Vec3 point = new Vec3(pos.getX() + 0.5D,
                pos.getY() + world.getValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS) / 8.0D, pos.getZ() + 0.5D);
        LookSpoof.Pose pose = null;

        if (Pacing.mayTurn())
        {
            Vec3 eye = player.getEyePosition();
            double dx = point.x - eye.x;
            double dy = point.y - eye.y;
            double dz = point.z - eye.z;
            pose = new LookSpoof.Pose((float) Math.toDegrees(Math.atan2(-dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), player.isShiftKeyDown());
        }

        EpDebug.say("снег " + EpDebug.at(pos) + ": слой кликом по верху (смотрят с грани " + hit.getDirection() + ")");

        return new Decision.Click(new BlockHitResult(point, Direction.UP, pos, false), pose, pos, world);
    }

    private static @Nullable Decision decideCount(BlockPos pos, BlockState schematic, BlockState world)
    {
        IntegerProperty property = BlockFamilies.countProperty(schematic);

        if (property == null)
        {
            return null;
        }

        boolean sameBlock = world.is(schematic.getBlock());

        if (! sameBlock)
        {
            return null;
        }

        int want = BlockFamilies.count(schematic, property);
        int have = BlockFamilies.count(world, property);

        if (have >= want)
        {
            return Decision.CANCEL;
        }

        if (BlockFamilies.isSegmentable(schematic))
        {
            Direction wanted = BlockFamilies.segmentFacing(schematic);
            Direction current = BlockFamilies.segmentFacing(world);

            if (wanted != null && current != null && wanted != current)
            {
                return Decision.CANCEL;
            }
        }

        if (AutoPlaceState.isConfirmationPending(pos, world, LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        return markOnly(pos, world);
    }

    private static Decision decideMultiface(ClientLevel level, BlockPos pos, BlockState schematic,
            BlockState world)
    {
        boolean sameBlock = world.is(schematic.getBlock());

        if (sameBlock && AutoPlaceState.isConfirmationPending(pos, world,
                LayerListConfigs.EP_CONFIRM_WINDOW.getIntegerValue()))
        {
            return Decision.CANCEL;
        }

        Direction wanted = null;

        for (Direction direction : Direction.values())
        {
            if (! MultifaceBlock.hasFace(schematic, direction))
            {
                continue;
            }

            if (sameBlock && MultifaceBlock.hasFace(world, direction))
            {
                continue;
            }

            BlockPos neighbour = pos.relative(direction);

            if (! MultifaceBlock.canAttachTo(level, direction, neighbour, level.getBlockState(neighbour)))
            {
                continue;
            }

            wanted = direction;
            break;
        }

        if (wanted == null)
        {
            return Decision.CANCEL;
        }

        return look(wanted, pos, world);
    }

    private static boolean stillCounting(BlockState schematic, BlockState world)
    {
        IntegerProperty property = BlockFamilies.countProperty(schematic);

        return property != null
                && BlockFamilies.count(world, property) < BlockFamilies.count(schematic, property);
    }

    private static Decision decideFaceAttached(ItemStack stack, BlockState schematic, BlockState world,
            BlockHitResult hit)
    {
        if (! FaceAttach.is(schematic)
                || schematic.getBlock().asItem() != stack.getItem() || world.is(schematic.getBlock()))
        {
            return Decision.PASS;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        LookSpoof.Pose pose = player == null ? null : FaceAttach.pose(schematic, player.isShiftKeyDown());

        if (pose == null || ! Pacing.mayTurn())
        {
            return Decision.PASS;
        }

        Direction support = FaceAttach.support(schematic);
        BlockPos target = hit.getBlockPos().relative(hit.getDirection());
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos cell = level != null && level.getBlockState(hit.getBlockPos()).canBeReplaced()
                ? hit.getBlockPos()
                : target;
        BlockHitResult own = new BlockHitResult(FaceAttach.spot(cell, support), support.getOpposite(),
                cell, false);

        return new Decision.Click(own, pose, null, null);
    }

    private static Decision look(Direction direction, BlockPos pos, BlockState world)
    {
        if (! Pacing.mayTurn())
        {
            return markOnly(pos, world);
        }

        LocalPlayer player = Minecraft.getInstance().player;
        float yaw = player == null ? 0.0F : player.getYRot();
        boolean sneak = player != null && player.isShiftKeyDown();

        LookSpoof.Pose pose = switch (direction)
        {
            case UP -> new LookSpoof.Pose(yaw, -90.0F, sneak);
            case DOWN -> new LookSpoof.Pose(yaw, 90.0F, sneak);
            default -> new LookSpoof.Pose(direction.toYRot(), 0.0F, sneak);
        };

        return new Decision.Click(null, pose, pos, world);
    }

    private static Decision markOnly(BlockPos pos, BlockState world)
    {
        return new Decision.Click(null, null, pos, world);
    }

    private static @Nullable WorldSchematic schematicWorld()
    {
        try
        {
            return SchematicWorldHandler.getSchematicWorld();
        }
        catch (RuntimeException | LinkageError e)
        {
            return null;
        }
    }
}
