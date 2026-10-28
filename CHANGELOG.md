# Changelog

## 1.0.22+26.2 — 26.10.2026

**RU**

**new — проверка блоков (Ctrl+B) подсвечивает и сущности схемы.** Подушки, стойки, рамки, картины: красная рамка — сущности нет или в рамке нет предмета, жёлтая — стоит не так (повёрнута, другой цвет подушки, другой предмет в рамке). Те же слои, область и дальность, что у блоков; число попадает в итог проверки.

**fix — флаг с узором не ставился, если в руке был флаг того же цвета без узора (или с другим узором).** Easy Place думал, что нужный флаг уже в руке, и не брал его из рюкзака — клетка оставалась пустой, пока флаг не возьмёшь сам. Чаще всего так бывало с забитым хотбаром. Теперь в руку берётся флаг именно с нужным узором; так же и наоборот — простой флаг, когда в руке узорный.

**fix — вода и лава из схемы не засчитывались построенными.** Налитый источник там, где схема просит воду (фонтан, пруд), всё равно числился «нет» — строка «Ведро воды» не уменьшалась никогда.

**fix — с «Считать до Y» блоки считались только на текущем слое.** Если в Litematica выбран режим слоёв (не «Все»), список брал блоки текущего слоя, а сущности — всё до высоты Y. Теперь и блоки считаются до высоты Y, как обещает настройка. В таблице «Материалы» рамка на стене слоя-границы больше не выпадает из счёта.

**fix — флаги, вазы и головы в шалкере или мешке в HUD не засчитывались «с собой».** Строки с узором краснели, хотя всё было в шалкере в инвентаре; у обычных блоков шалкеры учитывались. Теперь одинаково.

**fix — уровни света сливались в одну строку таблицы «Материалы».** Теперь у каждого уровня своя строка, как в HUD.

**fix — после удаления последнего размещения «Список HUD» в таблице показывал старые строки.**

**new — замок в редакторе размещения.** Кнопка с замком справа от «Положение»: ввели координаты, нажали — схема больше не сдвинется ни мышью, ни клавишами, ни инструментом Litematica (это замок самой Litematica). Закреплённое размещение видно по золотому замку и золотой рамке в мире; поля, «−/+», поворот, отражение, «Ко мне» и «К земле» у него серые. Во вкладке «Размещения» у закреплённого золотой замок в строке, а в меню правой кнопки — «Закрепить» / «Снять закрепление». «Отмена» в редакторе возвращает и замок, каким он был. Общим размещением Syncmatica управляет сама — у него замок не нажимается.

**new — «Смотреть на мир» в редакторе размещения**, как во вкладке «Выделение». Редактор закрывается, можно ходить и летать, а карточка размещения с координатами остаётся сверху справа и показывает их вживую. R — курсор к карточке: поля, «+/−» (Shift — по 10, Ctrl — по 100), поворот, отражение, «Ко мне», «Двигать в мире» и замок. Ещё раз R или щелчок мимо карточки — снова игра, Esc — обратно в редактор; «Отмена» и «Отменить шаг» помнят всё, что сделано из карточки.

**new — кнопки редактора размещения выделены цветом по смыслу.** «Готово» — зелёная, «Отмена» — красным пунктиром, «−/+» — цветом своей оси, поворот и отражение — синей рамкой, «Ко мне» и «К земле» — синим пунктиром, «Смотреть на мир» — синяя, замок — золотой (пунктиром, пока открыт). Во вкладке «Размещения» «Материалы» — синим пунктиром.

**fix — ввод числа в поле координаты, закреплённой в Litematica по оси, молча ничего не делал.** Теперь поле серое, а редактор говорит, что ось закреплена.

**new — переименование схем в «Библиотеке».** Правый клик по схеме → «Переименовать…». Расширение файла остаётся прежним. Вместе с файлом на новое имя переходят размещения этой схемы в мире (после смены измерения и перезахода они не пропадут), замены и отключения блоков из «Материалов», зоны Zone Focus и миниатюра. Если имя занято или в нём есть знаки, которые Windows не принимает (`\ / : * ? " < > |`), окно не закроется и скажет почему. После переименования в сообщении есть «Отменить» — оно вернёт старое имя вместе со всем перечисленным. Имя схемы внутри файла и имена размещений не меняются.

**fix — во вкладке «Материалы» не каждую строку можно было отключить.** У «Светящихся ягод», мака в горшке, земли под грядкой и других строк, где предмет в списке не совпадает с блоком схемы, в меню не было «Отключить». Теперь отключается любая строка: пропадают все клетки, которые список считал ею (у ягод — вся лоза целиком). Растение в горшке и вода в котле отключаются вместе с горшком или котлом — об этом сказано в подсказке пункта.

**fix — Easy Place не ставил нить.** Клетка с нитью молча пропускалась и в выживании, и в творческом. Теперь нить ставится, как обычный блок.

**new — Easy Place зажигает огонь.** Огонь и огонь душ из схемы ставятся огнивом (нет огнива — огненным зарядом): мод щёлкает им по блоку под клеткой, как сделал бы игрок. Огонь душ получается сам, если внизу земля или песок душ.

**new — редстоун, рельсы и нить видны в голограммах.** Раньше подсветка «блока нет» закрывала их сплошным голубым квадратом, и не было видно, куда идёт дорожка. Теперь у них только рамка клетки, а сам узор виден. Настройки → Ghost Alpha → «Редстоун, рельсы и нить без заливки» (по умолчанию включено; выключите, чтобы было как в Litematica).

**EN**

**new — block verification (Ctrl+B) highlights schematic entities too.** Cushions, armor stands, item frames, paintings: a red box means the entity is missing or the frame is empty, a yellow one means it stands wrong (rotation, another cushion colour, another item in the frame). Same layers, area and range as blocks; the count goes into the verification summary.

**fix — a patterned banner was not placed while a banner of the same colour without a pattern (or with another one) was in hand.** Easy Place thought the right banner was already in hand and did not take it from the backpack, so the cell stayed empty until you took the banner yourself. It happened mostly with a full hotbar. Now the banner with exactly the right pattern is taken into the hand; the same for a plain banner while a patterned one is in hand.

**fix — water and lava from the schematic were never counted as built.** A poured source where the schematic wants water (fountain, pond) still counted as missing, so the Water Bucket row never went down.

**fix — with "Count up to Y" blocks were counted on the current layer only.** With a Litematica layer mode other than All, the list took blocks of the current layer and entities up to Y. Blocks are now counted up to Y as the setting promises. In the Materials table a frame on a wall of the boundary layer is no longer left out.

**fix — banners, pots and heads inside a shulker box or bundle did not count as "with you" in the HUD.** Patterned rows stayed red although everything was in a shulker in the inventory; plain blocks counted shulkers. Now both do.

**fix — Light levels were merged into one row of the Materials table.** Each level has its own row now, as in the HUD.

**fix — after removing the last placement, "HUD list" in the table still showed the old rows.**

**new — padlock in the placement editor.** A padlock button to the right of "Position": type the coordinates, press it, and the schematic can no longer be moved by the mouse, the keys or Litematica's tool (this is Litematica's own lock). A locked placement shows a gold padlock and a gold frame in the world; its fields, −/+, rotate, mirror, "To me" and "To ground" are grey. In the Placements tab a locked placement has a gold padlock in its row, and the right-click menu has "Lock" / "Unlock". "Cancel" in the editor restores the lock as it was. Syncmatica manages shared placements itself, so their padlock cannot be pressed.

**new — "Look at the world" in the placement editor**, like in the Selection tab. The editor closes, you can walk and fly, and a placement card with live coordinates stays in the top right. R puts the cursor on the card: fields, +/− (Shift — by 10, Ctrl — by 100), rotate, mirror, "To me", "Move in world" and the padlock. R again or a click outside the card returns to the game, Esc returns to the editor; "Cancel" and "Undo step" remember everything done from the card.

**new — placement editor buttons are coloured by meaning.** Done is green, Cancel has a red dashed edge, −/+ use their axis colour, rotate and mirror a blue edge, "To me" and "To ground" a blue dashed edge, "Look at the world" is blue, the padlock gold (dashed while open). In the Placements tab "Materials" has a blue dashed edge.

**fix — typing into a coordinate locked by axis in Litematica silently did nothing.** The field is grey now and the editor says the axis is locked.

**new — rename schematics in the Library.** Right-click a schematic → "Rename…". The file extension stays. Placements of the schematic in the world move to the new name together with the file (they are not lost after a dimension change or rejoining), and so do block replacements and disabled blocks from Materials, Zone Focus zones and the thumbnail. A taken name or characters Windows does not accept (`\ / : * ? " < > |`) keep the window open with the reason. The message after renaming has "Undo", which brings the old name back along with all of the above. The schematic name stored inside the file and placement names do not change.

**fix — not every row of the Materials tab could be disabled.** Glow Berries, a poppy in a pot, dirt under farmland and other rows whose item differs from the schematic block had no "Disable" in the menu. Every row can be disabled now: all cells the list counted as that row go away (for glow berries, the whole vine). A plant in a pot and water in a cauldron are disabled together with the pot or cauldron; the menu tooltip says so.

**fix — Easy Place did not place string.** A string cell was silently skipped in survival and creative. String is placed like a normal block now.

**new — Easy Place lights fire.** Fire and soul fire from the schematic are placed with flint and steel (without one, with a fire charge): the mod clicks the block under the cell with it, as a player would. Soul fire comes by itself when soul soil or soul sand is below.

**new — redstone, rails and string are visible in holograms.** The "missing block" overlay used to cover them with a solid blue square, so you could not see where the line goes. Now they get only the cell frame and the pattern itself is visible. Settings → Ghost Alpha → "Redstone, rails and string without fill" (on by default; turn it off for the Litematica look).

## 1.0.21+26.2 — 24.10.2026

**RU**

**fix — длинная строка внизу HUD растягивала панель на пол-экрана.** Например, «Снимок из Оверворлда: <имя размещения> — итоги заморожены, инвентарь живой» с длинным именем. Теперь строки внизу панели (снимок, страницы, чанки, строки аддонов) ширину не задают: если не влезают, они бегут внутри панели. Длинная подпись области от аддона справа в заголовке тоже бежит, а не раздувает панель.

**fix — Easy Place не ставил невидимый блок света.** У блока «Свет» нет контура, пока предмет не в руке, и прицел проходил сквозь голограмму — клика не было вовсе. Теперь клетка со светом находится, а в руку берётся «Свет» ровно нужного уровня (раньше вставал уровень 15 вместо 7). Нужного уровня в инвентаре нет — клетка пропускается, а не заполняется чужим уровнем. В творческом нужный уровень берётся сам.

**fix — в списке материалов все уровни света слипались в одну строку.** Теперь у каждого уровня своя строка: «Свет 15», «Свет 7» и т. д. — сразу видно, какой свет принести.

**fix — меню и выпадающие списки растягивались под самое длинное имя схемы.** Например, выбор схемы во вкладке «Материалы» или «Источник» в окне выгрузки. Теперь очень длинное имя показано с многоточием, а под курсором бежит целиком.

**EN**

**fix — a long line at the bottom of the HUD stretched the panel across half the screen.** For example "Snapshot from Overworld: <placement name> — totals frozen, inventory is live" with a long name. Footer lines (snapshot, pages, chunks, add-on lines) no longer set the panel width: when they do not fit, they scroll inside the panel. A long add-on area label at the right of the title scrolls too instead of widening the panel.

**fix — Easy Place did not place the invisible Light block.** A Light block has no outline unless the item is in hand, so the aim went straight through the hologram and no click happened at all. The light cell is now found, and the Light item of exactly the right level is taken (it used to place level 15 instead of 7). With no item of that level in the inventory the cell is skipped instead of getting a wrong level. In creative the right level is picked by itself.

**fix — all Light levels were merged into one row of the material list.** Each level now has its own row: "Light 15", "Light 7" and so on, so you see which light to bring.

**fix — menus and dropdowns grew to fit the longest schematic name.** For example the schematic picker in the Materials tab or "Source" in the export window. A very long name is now shown with an ellipsis and scrolls in full under the cursor.


## 1.0.20+26.2 — 23.10.2026

**RU**

**new — «С учётом склада» в HUD.** Настройки → HUD → «Содержимое панели» → «С учётом склада» (по умолчанию выключено). Панель прячет то, что уже есть у вас в инвентаре и в сундуках, бочках и шалкерах, которые вы открывали на этом сервере в этом измерении: если для схемы нужно 144 земли и они лежат в сундуке, строки «Земля» не будет. Остаётся только то, чего не хватает совсем; «нет N» тоже считает сундуки, внизу панели — сколько строк спрятано. Сундук должен быть хотя бы раз открыт, иначе мод не знает, что в нём. Пока открыто хранилище, список полный — «Перенести всё необходимое» забирает и спрятанное.

**new — тумблер «С учётом склада» во вкладке «Материалы» стал той же настройкой**, что в HUD: запоминается между запусками и работает без «Только недостающего».

**fix — подсказка колонки «В сундуках» говорила, что содержимое шалкеров в сундуках не видно.** На деле шалкеры и мешки в сундуках давно учитываются; текст исправлен.

**EN**

**new — "Count storage" for the HUD.** Settings → HUD → Panel content → "Count storage" (off by default). The panel hides what you already have in your inventory and in chests, barrels and shulker boxes you have opened on this server in this dimension: if the schematic needs 144 dirt and it is in a chest, the Dirt row is gone. Only what is missing entirely stays; "need N" counts chests too, and the footer says how many rows are hidden. A chest must be opened at least once, otherwise the mod does not know what is inside. While a storage is open the list is full, so "Move everything needed" also takes the hidden items.

**new — the "Count storage" toggle in the Materials tab is now the same setting** as in the HUD: it is remembered between launches and works without "Only missing".

**fix — the "In chests" column tooltip said shulker box contents in chests are not seen.** Shulker boxes and bundles inside chests have been counted for a while; the text is fixed.

## 1.0.19+26.2 — 22.10.2026

**RU**

**new — оформление: Aell UI, Vanilla или Vanilla+.** Настройки → HUD → «Стиль интерфейса» → «Оформление». По умолчанию Aell UI — всё как раньше. **Vanilla** переводит все окна мода на вид Minecraft: настройки, библиотеку схем, таблицу материалов, выгрузку, диалоги, редактор размещения, HUD-панель, уведомления и подсказки — ванильные кнопки, ползунки, поля, вкладки, рамки подсказок и всплывающих окон, размытый фон и щелчок кнопки Minecraft. Экран настроек в Vanilla — плитки «Название: значение» в две колонки, как «Настройки» игры (правый клик по плитке — сбросить). **Vanilla+** — то же, но настройки строками, как у Litematica: название слева, справа плитка значения и «Сброс». Окна самой Litematica в обоих режимах остаются ванильными (перекраска в цвета мода выключается). Zone Focus и EasyPlaceFix+ берут оформление Material HUD, если у них не выбрано своё.

**new — «Считать до Y».** Настройки → HUD → «Счёт до высоты»: список материалов считает всё на высоте Y и ниже (от −60 до 321), а в мире по-прежнему виден только текущий слой. Тот же переключатель есть на вкладке «Материалы» окна схем; для схемы из библиотеки высота считается по координатам самой схемы.

**EN**

**new — Look: Aell UI, Vanilla or Vanilla+.** Settings → HUD → Interface style → Look. Aell UI is the default — nothing changes. **Vanilla** switches every window of the mod to Minecraft's own look: settings, schematic library, materials table, export, dialogs, placement editor, HUD panel, toasts and tooltips get vanilla buttons, sliders, fields, tabs, tooltip and pop-up frames, a blurred background and the Minecraft click sound. The settings screen in Vanilla is tiles "Name: value" in two columns like Minecraft's Options (right-click a tile to reset it). **Vanilla+** is the same, but settings are rows like Litematica's: name on the left, value tile and Reset on the right. Litematica's own screens stay vanilla in both (the mod's recolor of them is off). Zone Focus and EasyPlaceFix+ follow Material HUD's look unless they pick their own.

**new — "Count up to Y".** Settings → HUD → "Count up to a height": the materials list counts everything at or below Y (−60 to 321) while the world still shows only the current layer. The same toggle is in the Materials tab of the schematics window; for a library schematic the height uses the schematic's own coordinates.

## 1.0.18+26.2 — 21.10.2026

**RU**

**new — воду и лаву можно отключить.** Строка ведра воды или лавы по-прежнему не заменяется на другой блок (теперь так и написано), зато «Отключить блок» работает: вода пропадает из схемы, а плиты, ступени и другие блоки «в воде» высыхают. Золотая кнопка возвращает всё как было.

**new — можно отключить сущности схемы.** Рамки, стойки для брони, картины, вагонетки, лодки и кристаллы Энда: «Отключить блок» убирает их из голограмм, HUD, таблицы, выгрузки и Easy Place. Строка предмета в рамке снимает только предмет, рамки остаются; строка рамки убирает рамку вместе с содержимым. Золотая кнопка возвращает всё в точности, отключение переживает перезапуск игры.

**new — «Отключить блок» в «Списке HUD».** Блок отключается сразу во всех включённых размещениях, где он есть, и так же возвращается обратно.

**new — в выгрузке отключённые блоки в таблицу не идут**, окно выгрузки об этом предупреждает; вернуть блоки можно во вкладке «Материалы».

**fix — «Только недостающее» в списке схемы ничего не делало.** Источник «Схема: …» считал файл целиком и с миром не сверялся. Теперь размещённая схема сверяется с миром по всему размещению (слои Litematica не важны), лишнее из таблицы и выгрузки уходит. Слои Litematica и голограмма не важны: размещение можно и выключить. С включённой кнопкой колонка «Всего» становится «Осталось» — сколько ещё поставить, как в «Списке HUD»; строки идут по остатку. Неразмещённая схема — кнопка неактивна и объясняет почему.

**fix — пропадала голограмма сущности, если схема размещена дважды.** Litematica перестраивает голограммы в нескольких потоках, а у двух размещений одной схемы сущности с одинаковыми id: изредка поток затирал сущность соседнего, и одна рамка или стойка пропадала из голограммы насовсем. Теперь сущности в мир схемы добавляются по одной.

**EN**

**fix — «Only missing» did nothing in a schematic's list:** a placed schematic is now compared with the world over the whole placement (Litematica layers do not matter); an unplaced one has the toggle disabled with an explanation. **fix — an entity hologram could vanish when a schematic was placed twice** (a race between Litematica's rebuild threads); entities now enter the schematic world one at a time.

**new — water and lava can be disabled** (waterlogged blocks are dried), **entities of a schematic can be disabled too** (item frames, armor stands, paintings, minecarts, boats; a frame-content row strips the item and leaves the frames), **«Disable block» works in the HUD list** (in every enabled placement's schematic), and the export window says that disabled blocks are left out of the table.

## 1.0.7+26.3 / 1.0.7+26.2 — 12.10.2026 (beta)

**RU**

**new — облёт не проходит сквозь блоки.** Просьба: в режиме размещения «Облёт» камера пролетала через землю и стены. Теперь глаз камеры — тело: он не прыгает в расчётную позу облёта, а каждый кадр едет к ней от своего прошлого места через ванильную проверку столкновений с блоками мира (`Entity.collideBoundingBox`, коробка ±0,25 блока), со скольжением вдоль стен, как у игрока. Вращение, колесо, панорама и F упираются в пол и стены; освободился путь — камера догоняет позу. Голограммы схемы и жидкости не мешают. Лучи курсора (`CursorRay`) идут из настоящего глаза, поэтому клики совпадают с картинкой. Проверено харнессом `k` (`LL_K_ONLY=edit`): облёт снизу при тангаже −80° — глаз на полу, а не под ним; стена камня за камерой и отъезд колесом — камера встала у стены, расчётная поза за ней; стена убрана — догнала.

**EN**

**new — the orbit camera no longer flies through blocks.** In the placement editor's Orbit view the camera eye is now a body: each frame it moves toward the orbit pose through vanilla block collision (sliding along walls like a player) instead of jumping into it. Rotating, the wheel, panning and F stop at the ground and walls; when the way is clear, the camera catches up. Holograms and liquids don't block it. Cursor rays start at the real eye, so clicks match the picture.

## 1.0.6+26.3 / 1.0.6+26.2 — 10.10.2026

**RU**

**new — тумблер «Бросать клетку после отказов сервера» (вкладка Easy Place, по умолчанию вкл).** Жалоба: игрок перебирает торги жителя кафедрой — ставит её Easy Place, ломает, ставит снова; после «сервер слишком много раз отказал по клетке … — пропускаю её» кафедра на это место больше не вставала. Раньше после шести подтверждённых отказов сервера клетка бросалась, пока в ней не изменится блок, — а в пустой клетке он не меняется, так что запрет держался до перезахода. Выключенный тумблер отказы не считает: клетку не бросаем, в чат не пишем (`easyPlaceGiveUpRefused`, общий с Easy Place Fix+). Сообщение об отказе теперь подсказывает этот тумблер. Самопроверка `LL_COVERAGE=refuse`: серверный `UseBlockCallback` отвечает FAIL на клик в клетку кафедры — вкл: ровно одно сообщение, клетка брошена; выкл: ни одного, кафедра встаёт, как только сервер не против. С этим модом и с Easy Place Fix+ — 7/7.

**fix — брошенная клетка не давала поставить блок и руками.** Запрет после отказов сервера относится только к кликам Easy Place: обычный правый клик без Easy Place (клавиша активации не зажата) ставит блок как всегда.

**Версия под Minecraft 26.2.** Тот же 1.0.6 собран и под 26.2 (Litematica 0.28.8, malilib 0.29.6): `1.0.6+26.2`. Отличия от 26.3 — только то, чего в 26.2 нет: подушек и древесных грибов (их Easy Place и доводка пропускает), таблицы инструментов читаются из ShovelItem / HoeItem / AxeItem, иконки и 3D-превью рисуются через подмену цели вывода (как ванильный PIP 26.2), таблички — старым пакетом. Файлы aell-ui, которые под 26.2 отличаются, лежат в `aell-ui-26.2/` и кладутся поверх общей aell-ui при сборке.

**EN**

**new — "Give up on cells the server refuses" toggle (Easy Place tab, on by default).** After six confirmed server refusals Easy Place stopped clicking a cell until its block changed — and an empty cell never changes, so a lectern for rerolling villager trades could not be placed there again. With the toggle off, refusals are not counted: the cell is never given up and nothing is written to chat (`easyPlaceGiveUpRefused`, shared with Easy Place Fix+). The refusal message now points at this toggle.

**fix — a given-up cell blocked placing by hand too.** The ban applies only to Easy Place clicks; a plain right click without Easy Place places as usual.

**Minecraft 26.2 build.** The same 1.0.6 is built for 26.2 as well (`1.0.6+26.2`, Litematica 0.28.8, malilib 0.29.6). Only what 26.2 lacks is left out: cushions and shelf mushrooms.

## 1.0.5+26.3 — 10.10.2026

**RU**

**new — кнопка «Auto» рядом с «Перенести всё необходимое».** Маленький переключатель справа от кнопки переноса: включён — светится золотом (мягкий ореол), выключен — в цветах большой кнопки. Включён — при открытии сундука, бочки, шалкера, раздатчика или воронки нужное для текущей страницы панели забирается само, ровно нехватка, как по кнопке; если брать нечего — молча. Печи, верстаки и торговля не трогаются. Срабатывает, когда сервер прислал содержимое контейнера (номер состояния меню не 0) и панель с кнопкой на экране; включили в открытом сундуке — забирает сразу. Наведение на «Auto» показывает на большой кнопке, что она делает. Тот же тумблер в настройках: «Автоперенос при открытии» (`hudTransferAuto`, выкл).

**fix — перенос иногда забирал больше, чем не хватает.** Две причины. Первая: «есть с собой» бралось из строки панели, а она обновляется с задержкой — второй сундук подряд (а с «Auto» это обычное дело) забирал ту же нехватку ещё раз. Теперь в момент переноса инвентарь считается заново (как у Litematica: с шалкерами и мешками, по её настройке — и с эндер-сундуком). Вторая — на сервере: щелчки по части стопки иногда расходятся с предсказанием клиента. После ответа сервера (два пинга с запасом) итог сверяется с целью «было + нехватка»: лишнее возвращается в контейнер (не больше пришедшего), недобор добирается ещё одним проходом, всего до трёх сверок. Лог: `[layerlist] перенос: сверка с сервером`.

**fix — схема и подсветка на долю секунды пропадали.** Режим «только блоки из инвентаря» каждые 5 тиков сверял инвентарь и при любой смене видимости пересобирал всю схему (`updateAll`), а Litematica 0.29 перед пересборкой чанка очищает его буферы — вся схема вместе с рамками Ctrl+B гасла на несколько кадров. Срабатывало и ложно: стопка на курсоре (щелчки переноса, перекладывание, изи-плейс) считалась пропавшей, а через тик «возвращалась» — второе мигание. Теперь стопка на курсоре считается «с собой»; тип прячется, только если предмета нет секунду подряд (появляется сразу); пересобираются лишь чанки, где фильтр встречал блоки этого типа, и их соседи по сторонам — не вся схема. «Скрывать блоки без опоры» пересобирает соседний чанк через границу, только если там в схеме есть ещё не поставленный блок, — рост посевов, вода и чужие постройки на сервере больше не мигают соседними чанками.

**fix — доводка не работала в режиме «только блоки из инвентаря».** Режим прятал клетку, если предмета блока схемы нет в инвентаре, — и горящий костёр, который надо потушить лопатой, для Easy Place становился пустым местом: доводка не запускалась, оверлей «не то состояние» не рисовался. Теперь стоящий блок, который можно довести инструментом из инвентаря (костёр лопатой, лоза ножницами, медь топором и т. д.), не прячется ни для прицела, ни в сборке чанка, ни в Ctrl+B; без инструмента — прячется, как раньше (невидимая клетка не перехватывает прицел у видимой). Появился или пропал инструмент — пересобираются чанки с такими клетками. Работает и для Easy Place Fix+ (он спрашивает через `EasyPlaceBridge.ghostHidden`).

**new — тыква вырезается ножницами по схеме.** В схеме вырезанная тыква, в мире целая — «Доводить блоки инструментом» режет её ножницами, кликом по грани, куда в схеме смотрит лицо (клик сверху взял бы направление от игрока). План с заданной гранью (`StateFix.Fix.clickFace` без точки) — точка на грани по взгляду.

**new — зоны Zone Focus в библиотеке схем.** С Zone Focus 0.0.8 в меню файла схемы — «Разбить на зоны…», а у разбитой — «Управление зонами…» и «Отменить разбиение» (красным). «Разбить на зоны…» открывает под превью панель: число зон по X, Y, Z (поля с вводом по центру, −/+, колесо, Shift — по 10, Enter — разбить), «Разбить на N зон», «Отменить разбиение» (второй щелчок за 3 секунды подтверждает), у размещённой схемы — «Управление зонами…». Схему разбивают без размещения: сетка хранится для файла и встаёт на размещение сама. Пока числа не совпадают с разбиением, на модели черновик синим. Зоны рисуются на превью каркасами с номером на верхней грани — те же цвета, что в мире (активная — циан, остальные — зелёный); наведённая выделена, в строке статуса «Зона 6 · 17×138×23»; щелчок по зоне размещённой схемы делает её активной. «Управление зонами…» у размещённой схемы открывает экран Zone Focus, у неразмещённой — панель в превью.

**new — вкладка Zone Focus: большая карточка «Управление зонами».** Вместо строки с кнопкой — живая карточка во всю ширину: слева мини-карта зон сверху (срез активной зоны, номера, готовность цветом, активная — циан), в центре «Портал · 15 зон · сетка 5×1×3», чипы «Активная 7», «Готово 17%», «Зон готово 2 из 15» и полоса из отрезков-зон по готовности, справа большая кнопка «Открыть ›» (зон нет — «Разбить на зоны ›», схемы нет — подсказка разместить). Раз в полсекунды перечитывает Zone Focus: сменили активную зону, построили блоки, разбили или отменили разбиение — карточка следует сама. Щелчок по карточке открывает «Управление зонами». В aell-ui — строка-карточка `Model.Option.card` (своя высота и рисунок).

**Вкладка Zone Focus: без раздела «Модуль».** Тумблер «Модуль Zone Focus» убран (с Zone Focus 0.0.8 модуль включён, пока стоит мод; выключить можно в его собственных настройках). «Настройки Zone Focus» — яркая залитая фиолетовым кнопка прямо в карточке, под «Открыть ›» («про другой мод», как его значок). Под карточкой — «3D-превью схем»: «Качество» / «Оптимизация» — то же, что качество превью библиотеки (`Model.Option.states` — свои подписи тумблера, и в строке «По умолчанию»). Со старым Zone Focus вкладка прежняя.

**new — железные защиты Easy Place: «Защита от установки мимо схемы» и «Защита от не того блока» (обе по умолчанию выкл).** В отличие от «Только по схеме» (та гасит только клики Easy Place) — для любой установки блока, и руками тоже, и только в пределах схемы: внутри коробок включённых регионов включённых размещений, за границей ставится всё, как обычно. «Мимо схемы» — в пустую клетку схемы не встаёт ничего; «Не тот блок» — в клетку, где схема ждёт блок, встаёт только он (сверка как в «Только по схеме», с горшками, землёй под дёрн и т. п.). Клетка, куда блок ляжет, считается ванильным `BlockPlaceContext`. Клик гасится до отправки на сервер, над хотбаром — «Мимо схемы: …» или «Не тот блок: …». Правый клик без приседа по сундуку, двери, рычагу и прочему, что съедает клик, проходит как обычно; мир схемы в клетке не загружен — защита молчит. С Easy Place Fix+ проверку ведёт он (опции его, тумблеры здесь правят их через мост). Самопроверка `LL_COVERAGE=strict`: четыре прохода (только одна, только другая, обе, обе выкл) — с этим модом и с Easy Place Fix+.

**fix — на скорости иногда вставал не тот блок (сервер, 10 блоков/с).** Причина — гонка между установкой и обменом предмета в руку. Блок A поставлен из руки — сервер потом пришлёт «в этом слоте A, на штуку меньше». Пока ответ в пути, Litematica уже меняет в руке A на B кликом по инвентарю (блок лежит в рюкзаке, не в хотбаре) и ставит B. Запоздавший ответ про A перезаписывает у клиента руку: клиент думает, что держит A, сервер — что B; следующий A клиент ставит «без обмена» — а сервер ставит B. Стенд `LL_COVERAGE=sync` воспроизводит это задержкой пакетов: пары «A, B, A, B» из рюкзака, пинг 150 мс — 5–6 не тех блоков из 24. Теперь обмен в руку (`swapItemToMainHand` malilib) ждёт подтверждения сервера на прошлую установку Easy Place (`ClientboundBlockChangedAckPacket` по номеру пакета; обновление слота руки сервер 26.3 рассылает в тике мира, раньше подтверждения, — к подтверждению ответ про руку уже дошёл); подтверждения нет 1,5 с — идёт как есть. Установка Easy Place ждёт два пинга после чужого клика по инвентарю (автопополнение Inventory Profiles Next и т. п.); свой обмен Litematica и перекладки для полок установку не задерживают. На стенде с защитой — 0 не тех из 24. Стенд при пинге 100 мс и 10 типах блоков: без защиты 6–10 не тех из 24 (10 и 20 блоков/с), с защитой — 0; когда блоки в хотбаре, скорость почти полная (9,4 и 15,6 блоков/с), когда каждый блок идёт обменом из рюкзака — около 6 блоков/с (один пинг на обмен). В одиночной игре — без ожиданий. Если сервер подтверждений не шлёт вовсе (старый сервер за ViaVersion) — обмен ждёт по пингу, как раньше, а не по 1,5 с. С Easy Place Fix+ то же делает он.

**Заголовки разделов настроек — с цветовым акцентом.** Цветная черта слева, название светлым тоном того же цвета, линия им же (aell-ui `Model.Section.accent`, по умолчанию — синий). Во вкладке Zone Focus цвета по смыслу: «Зоны» — циан, «Что считать» — золото, «Автопереключение» и «Схема» — синий, «Рамки зон» и «Инструмент» — зелёный, «Производительность» — оранжевый. «Инструмент» и «Интерфейс инструмента» — один раздел. Переключатель качества 3D-превью переехал в «Управление зонами» Zone Focus, рядом с 2D / 3D (`LayerListApi.isPreviewQuality` / `setPreviewQuality`).

**new — «Разбить на зоны…» в правой панели библиотеки.** Под «Разместить / Материалы / Экспорт» — кнопка цианом, как зоны в мире: схема не разбита — «Разбить на зоны…» (панель под превью), разбита — «Управление зонами…». Только с Zone Focus 0.0.8+.

**fix — автоперенос не работал, когда панель материалов скрыта клавишей (Y).** «Auto» ждал нарисованную кнопку переноса, а при скрытой панели её нет. Теперь «Auto» работает и со скрытой панелью: список материалов считается и без неё.

**new — Y (показать/скрыть панель материалов) в инвентаре и контейнерах.** Хоткеи malilib в открытом экране не срабатывают, поэтому клавишу ловит экран контейнера — там же, где ванилла проверяет клавишу инвентаря, после полей ввода: в поиске творческого инвентаря и книги рецептов буква печатается, а не прячет панель. Сочетание с модификаторами работает, если они зажаты; над хотбаром — «панель показана/скрыта».

**new — прогресс «блок в блок» на 3D-превью.** Модель размещённой схемы красится по сверке с миром: построенные блоки — как есть, непостроенные — светлым голубым «призраком», чужой блок на месте — красным; в строке статуса «построено N%». Ожидаемый блок — из мира схемы Litematica (уже повёрнутый, как размещение), чанк не загружен — блок как есть. Сверка идёт кусками по кадрам и повторяется раз в 2 секунды; поменялось — модель пересобирается в фоне, прошлая видна до готовности. Работает в режимах «Качество» и «Оптимизация» (у упрощённых кубов — по большинству блоков), только у снимка 1:1. В библиотеке — у размещённой схемы, если стоит Zone Focus; в 3D «Управления зонами» — с «Показывать прогресс».

**new — прогресс постройки зон на превью.** Если зоны схемы стоят на её размещении, каркас каждой зоны красится по готовности (зелёный — готово, жёлтый — начато, красный — не начато), у номера — процент; скан Zone Focus запускается сам и дальше следит за блоками. Кнопка «Зоны» в углу превью убирает и возвращает каркасы — посмотреть на постройку без полос.

**new — Zone Focus поверх Material HUD отдаёт проверку блоков сюда.** В API версия 5: `LayerListApi.toggleVerification()`, `isVerificationActive()`, `getVerificationSummary()`; там же `createZonePreview()` — 3D-превью схемы с зонами для «Управления зонами» Zone Focus (`api/ZonePreview`: файл, зоны в координатах схемы, прогресс, мышь, выбор зоны щелчком, виды камеры «Изометрия / Сверху / Спереди» — `setView`, и «карта сверху» для 2D — `setMap`, `setMapCut`: снимок строго сверху в повороте и отражении размещения, мышью не крутится, срез по высоте). Zone Focus 0.0.8 с этим API убирает свою вкладку «Проверка», а его Ctrl+P включает Ctrl+B (по активной зоне, когда включён счёт по зоне).

**new — блоки опоры с соседнего слоя входят в список материалов.** Когда включена «Опора с соседнего слоя», блоки опоры вне видимого слоя (потолок над подвесной табличкой, блок над ягодами и лианой) считаются в панели вместе со слоем — и кнопка «Перенести всё необходимое» забирает их тоже. Опция выключена — их в списке нет. Поставили опору — строка уменьшается (набор опор пересчитывается, список пересобирается).

**new — «Библиотека схем» в шапке настроек вместо вкладки «Схемы ›».** Вкладка-ссылка из ряда вкладок убрана; кнопка в шапке рядом с поиском переименована в «Библиотека схем» и выделена приглушённым акцентом (в aell-ui у кнопок шапки появился свой цвет: `Model.HeaderButton.accent`).

Проверено: `LL_COVERAGE=u` — один слой, опция вкл: камень ×4 в списке; после установки одной опоры — ×3; опция выкл — камня нет; снимок шапки — `LL_COVERAGE=m`. Перенос (`u`, дополнен): второй сундук подряд при «застывшей» строке панели — камень не взят повторно; щелчок по «Auto» в открытом сундуке включил и сразу забрал нехватку; «Auto» при открытии — без щелчка, ровно нехватка; сервер подкинул игроку 3 камня во время щелчков — сверка вернула их в сундук; один слой с опорой — перенос забрал камень потолка (3 опоры). `y` — настоящие события SDL по кнопке. Новый режим `LL_COVERAGE=finish`: при «только из инвентаря» горящий костёр и целая тыква видны, пока есть лопата и ножницы, без лопаты костёр прячется; Easy Place тушит костёр и вырезает тыкву лицом на восток, как в схеме; камень пропал из инвентаря на полсекунды — 0 пересборок; кончился — одна пересборка двух чанков, не всей схемы. Новый режим `LL_COVERAGE=zfverify` (с Zone Focus 0.0.8 в `run/mods`): вкладки «Проверка» у Zone Focus нет, его Ctrl+P включает и выключает Ctrl+B, своя проверка молчит. Регрессия: `o`, `v`.

**fix — свечи, яйца черепахи, морские огурцы, лепестки, сухие листья и снег не добирались до нужного количества.** Litematica, прежде чем взять нужный предмет в руку, спрашивает «можно ли дополнить клетку» ванильным `canBeReplaced` — по предмету, который в руке СЕЙЧАС. Стопка кончилась или в руке другой блок (свеча другого цвета, андезит) — ответ «нельзя», нужный предмет даже не берётся, и клетка навсегда остаётся недобранной: поставил по свече, отошёл к другим блокам, вернулся — добрать уже не выходит. Теперь эта проверка для счётных клеток идёт с предметом, который клетке нужен; его Litematica и берёт в руку следом. Стенд `LL_COVERAGE=build` (стройка «Тестовой схемы» Easy Place «как игрок») с мелкими стопками по 3 и пингом 60 мс воспроизводил: свеча 3 из 4, яйца 3 из 4, полевые цветы 2 из 3 — с правкой всё добирается.

**fix — в креативе «Только блоки из инвентаря» прятал всю схему.** Материалы в креативе бесконечны, а режим считал только то, что лежит в инвентаре: от схемы оставались сущности и блоки из хотбара. Теперь в креативе режим молчит — схема видна целиком, и проверка Ctrl+B тоже; вернулись в выживание — прячет снова, схема пересобирается сама. Хоткей режима в креативе так и говорит: «в креативе видно всё».

**fix — в креативе не доводились блоки, которым нужен инструмент.** Книги в резную полку, костная мука на пещерные лозы и трутовик, огниво, ножницы, топор, лопата, соты, ведро воды: инструмент искался только в инвентаре, а в креативе его там обычно нет — доводка не планировалась вовсе. Теперь в креативе инструмент берётся «из ничего», как блоки берёт pick-block Litematica.

**new — рамка опоры.** При «Скрывать блоки без опоры» блок, которому не на чем держаться, по-прежнему спрятан, а клетка его опоры из схемы, которую можно поставить прямо сейчас, обводится янтарной рамкой: видно, что строить первым — потолок над висячей табличкой, пол под сталагмитом. Звено той же цепочки (лоза под лозой) рамку не получает — её получает то, на чём держится начало. Ищется вокруг игрока (24 блока по горизонтали, 16 по высоте), с «Опорой с соседнего слоя» — и на соседнем слое; при «только из инвентаря» — только для блоков, которые у игрока есть. Самопроверка `LL_COVERAGE=frames`.

**fix — кольцо поворотных рельсов не ставилось вовсе.** Поворот без соседей встал бы прямым, поэтому решатель (при «Пропускать то, что встанет неверно») ждал соседа; в кольце (2×2 поворота, кольцо вокруг блока редстоуна) все ждали друг друга, и не вставал ни один. Теперь рельс ждёт только прямого соседа — тот встаёт верно сам; поворот ставится сразу, следующие подключаются к нему, и ванильная доводка формы загибает его. Стенд `LL_COVERAGE=build`: оба кольца «Тестовой схемы» встали.

**fix — список материалов недосчитывал лепестки, полевые цветы и сухие листья.** Клетке с тремя сегментами нужно три предмета, а Litematica считала один — и перенос из сундука привозил меньше, чем нужно. Теперь — по числу сегментов (как уже было у граней лианы).

**fix — «Наполнять блоки» не наливал котёл.** Под котёл с водой Easy Place ставит пустой котёл — у налитого своего предмета нет, — а доливка умела только добавлять уровень в уже налитый, и пустой оставался пустым навсегда. К тому же вода шла только ведром — сразу уровень 3. Теперь пустой котёл доводится: вода бутылками по уровню (полный можно и ведром), лава и рыхлый снег — своими вёдрами. В креативе бутылка и вёдра берутся «из ничего».

**new — голограммы сущностей и блок-сущностей полупрозрачные.** Litematica рисовала подушки, рамки с предметами, стойки, картины, вагонетки и сундуки схемы обычным непрозрачным рендером — до установки их было не отличить от настоящих. Теперь они полупрозрачные, как голограммы блоков (та же прозрачность «Ghost Alpha»), тени у них нет; пока прозрачность голограмм выключена — как раньше. Модели идут полупрозрачным двойником своего типа отрисовки, рамка и предмет в ней (блочные модели и предметы, в том числе сеткой Fabric Renderer API) — своими квадами с альфой. Самопроверка `LL_COVERAGE=entghost` (снимки с прозрачностью и без).

**new — снег слоем с любой стороны.** Ваниль добавляет слой снега, только если кликнуть по верхней грани. Теперь, когда игрок смотрит на снег сбоку или снизу, Easy Place сам кликает по верху этой клетки, а железные защиты и проверка Litematica «можно ли дополнить клетку» такой клик пропускают. Стенд `LL_COVERAGE=build` с подходом строго сбоку: все 8 клеток снега (1–8 слоёв) «Тестовой схемы» — как в схеме.

**fix — Easy Place не ставил подушку на снег в 5–8 слоёв на сервере («сервер трижды не принял установку»).** Снег у игры «заменяемый», и клик по нему мод уводил в блок под снегом — точка клика выходила дальше блока от середины кликнутого блока, а сервер такие клики молча выбрасывает. В одиночной игре этого не видно: там Litematica снимает с сервера эту проверку. Теперь клик идёт по клетке прямо под подушкой, что бы в ней ни лежало. В котёл, воронку и компостер сервер кладёт подушку туда, куда упирается взгляд игрока: мод кликает по точке дна, которую видно, а если дна не видно — пишет «встаньте выше» и не кликает (раньше подушка вставала на край воронки или не вставала вовсе); подушка на краю вместо дна больше не считается поставленной. Стойка на снегу без соседних блоков теперь тоже ставится. Стенд `LL_COVERAGE=cushion` с проверкой клика как на сервере: подушки «Тестовой схемы» на снегу 1–8 слоёв, на люке, в клетках забора и калитки, подушки в котле, на его краю, в воронке и компостнице, стойка на снегу — 16 из 16 и ни одной лишней; до правки — 7 промахов и подушка на краю воронки.

**EN**

**new — an "Auto" toggle next to the transfer button.** On (glows gold), opening a chest, barrel, shulker box, dispenser or hopper takes exactly what the current panel page is short of; silent when there is nothing to take. Also in settings: "Auto-transfer on open" (`hudTransferAuto`, off).

**fix — the transfer sometimes took more than needed.** What you carry is now counted live at transfer time (the panel row lags behind), and after the server answers the result is reconciled: extra items go back to the container, shortfalls get another pass.

**fix — the schematic and highlights blinked for a split second.** "Only blocks from inventory" no longer rebuilds the whole schematic on every inventory change: the stack on the cursor counts as carried, a type hides only after a second without it, and only chunks containing that type are rebuilt.

**fix — block finishing did not work with "only blocks from inventory".** A standing block you can finish with a tool you carry (campfire with a shovel, vines with shears…) is no longer hidden from aim, rendering or Ctrl+B.

**new — pumpkins are carved with shears to match the schematic**, face on the schematic's side.

**new — Zone Focus zones in the schematic library**: «Split into zones…», «Manage zones…», «Undo split» in the file menu; a split panel under the preview (per-axis fields, Split, Undo); split without placing — the grid is kept for the file and applied to its placement; zone frames with numbers on the model, click to make a zone active.

**new — build progress per zone on the preview** (green done, yellow started, red not started, percent next to the number); the «Zones» button in the preview corner hides the frames.

**new — Zone Focus tab: a big live «Manage zones» card** (zone mini-map, active zone, progress chips and a per-zone bar, a large «Open ›» button); the «Module» section is gone — «Zone Focus settings…» moved into «Zones» as a purple primary button, next to a «3D schematic preview: Quality / Optimized» switch.

**new — iron Easy Place guards: «Block placing off the schematic» and «Block placing the wrong block» (both off by default)**: for every block placement, by hand too, and only within the schematic — the first keeps empty schematic cells empty, the second lets a cell take only the block the schematic wants; outside the schematic everything places as usual; chests, doors, levers still open.

**fix — fast building on a server sometimes placed the wrong block.** A late server update for the hand slot (from the previous placement) overwrote the item Litematica had just swapped in from the inventory, so the client and server disagreed about the held item. Swapping an item into the hand now waits for the server to confirm the previous Easy Place click; Easy Place clicks wait briefly after other mods' inventory clicks. Blocks in the hotbar keep full speed; singleplayer is unaffected.

**fix — candles, turtle eggs, sea pickles, petals, leaf litter and snow layers did not reach the required count.** Before taking the needed item, Litematica asks the vanilla «can this cell be added to» check using the item currently in hand; with an empty hand or a different block it said no and never took the right item. The check now uses the required item.

**fix — in creative, «Only blocks from inventory» hid the whole schematic.** Materials are infinite in creative, so the mode now stays silent there and the whole schematic is shown; back in survival it hides again.

**fix — in creative, finishing that needs a tool did nothing** (books into a chiseled bookshelf, bone meal on cave vines, flint and steel, shears, axe…): the tool is now conjured like Litematica's creative pick-block.

**new — support frame**: with «Hide blocks without support», the support cell a hidden block needs (the ceiling above a hanging sign, the floor under a stalagmite) gets an amber frame, so you can see what to build first.

**fix — a ring of curved rails was never placed**: every curve waited for its neighbours; rails now wait only for straight neighbours, curves are placed right away and vanilla bends them as neighbours arrive.

**fix — «Fill blocks» never filled a cauldron**: Easy Place places an empty cauldron and the fill step did not handle it; water now goes in bottle by bottle up to the level (a full one may take a bucket), lava and powder snow by bucket.

**new — schematic entity and block-entity holograms are translucent**: cushions, item frames, armor stands, paintings, minecarts and chests from the schematic no longer look like real ones before you place them; they use the same ghost transparency as blocks.

**new — snow layers from any side**: looking at snow from the side or below, Easy Place clicks the top of that cell, so layers are added without looking down at it.

**fix — on servers Easy Place did not place cushions on snow 5–8 layers deep ("the server rejected it three times").** Snow counts as replaceable, so the click went to the block under it and landed more than a block from that block's centre, which servers silently drop (singleplayer skips that check). The click now goes to the cell right under the cushion. For cauldrons, hoppers and composters the server drops the cushion where your line of sight lands, so Easy Place clicks a visible spot of the bottom or asks you to stand higher, instead of leaving the cushion on the rim. An armor stand on snow with no neighbouring blocks is placed too.

**fix — the material list undercounted pink petals, wildflowers and leaf litter**: a cell with N segments now counts N items, so transfers bring enough.

**Settings section headers get colour accents**; in the Zone Focus tab «Tool» is one section and the 3D preview quality switch moved into «Manage zones» next to 2D / 3D.

**new — «Split into zones…» button in the library info panel** (with Zone Focus).

**fix — auto-transfer did not run while the material panel was hidden (Y).**

**new — Y toggles the material panel inside the inventory and containers** (text fields keep their letters).

**new — block-by-block progress on the 3D preview**: built blocks as they are, missing ones as a light blue «ghost», wrong blocks red; «built N%» in the status line.

**new — API 5: Zone Focus on top of Material HUD hands block verification to Ctrl+B**, and gets a 3D schematic preview with zones (`LayerListApi.createZonePreview`) with Isometric / Top / Front camera presets and a fixed top-down map mode for its 2D view.

**new — support blocks from the next layer are counted in the material list** while "Support from the next layer" is on, so the transfer button takes them too.

**new — "Schematic library" in the settings header replaces the "Schematics ›" tab**, highlighted with a muted accent.

## 1.0.4+26.3 — 09.10.2026

**RU**

**fix — в инвентаре игрока, верстаке и печах панель ложилась поверх подсказки предмета.** Экраны с книгой рецептов (`AbstractRecipeBookScreen`) рисуются своим `extractRenderState`, без `AbstractContainerScreen.extractRenderState`, — правило «панель под подсказкой» из 1.0.2 их не касалось, и панель рисовал InfoHud уже после подсказки. Теперь панель ставится и в них — перед предметом на курсоре и подсказкой.

**new — в меню наших модов панель прячется.** Пока открыто меню Material HUD, Zone Focus, Easy Place Fix+, Ghost Alpha, aell-ui и других наших модов (экраны из пакетов `com.aell.` и `dev.aell.`), панель материалов не рисуется и не ложится поверх настроек; закрыли меню — панель на месте. Экран расположения панели — исключение.

**fix — в режиме «только блоки из инвентаря» видимую голограмму нельзя было поставить сквозь невидимую.** Голограмма, которую режим не рисует (предмета нет в инвентаре), по-прежнему ловила луч Litematica: Easy Place наводился на пустое с виду место перед видимой голограммой и молча не ставил ничего. Теперь луч по миру схемы (`RayTraceUtils.rayTraceBlocks` и `rayTraceBlocksToList`, только лучи с учётом слоя) проходит такие клетки насквозь, как пустые; доводка прицела (`AirTarget`) их пропускает. То же — для «скрывать блоки без опоры». Только недостроенные клетки: у готовых прицел как у Litematica. Easy Place Fix+ спрашивает то же через мост (`EasyPlaceBridge.ghostHidden`).

**fix — дыры в голограммах рядом со скрытыми блоками.** Грань видимой голограммы отсекалась о соседа по схеме, даже если сосед скрыт режимом «только из инвентаря» или «без опоры», — в непрозрачной голограмме зияла дыра внутрь блока. Теперь грань к скрытому соседу рисуется (`shouldRenderModelSide`, срез мира игрока у собираемого чанка).

**new — «Только блоки из инвентаря» включает внутренние грани Ghost Alpha.** При включении режима включаются «Внутренние грани» Litematica, если были выключены; при выключении режима они возвращаются как были (включённые самим игроком не трогаются).

**new — кнопка «Перенести всё необходимое» в открытом контейнере.** Над панелью у сундука, бочки, шалкера и других контейнеров с хранилищем (не в инвентаре игрока). Верх кнопки — вровень с верхом окна контейнера и не выше него, панель встаёт под кнопку (`HudDock.beside` с запасом сверху; по высоте не влезает — панель ужимается, не помогает — стоит как раньше, без кнопки). Щелчок забирает из контейнера всё, что есть из текущей страницы панели, пока у строки не закрыта нехватка (недостающее минус то, что уже с собой), — ровно нехватку: нужная целиком стопка уходит Shift+кликом (`QUICK_MOVE`), из большей берётся только нехватка (стопка на курсор, по одному в слот инвентаря или лишнее по одному обратно — что короче, остаток на место), не больше 4 щелчков за тик; флаги, узорчатые вазы и головы — только с тем же узором. Слоты игрока, выход крафта и торговли не трогаются. Щелчок ловится на входе `MouseHandler.onButton`, до экрана и чужих оверлеев; левая кнопка — `InputConstants.MOUSE_BUTTON_LEFT` (в 26.3 номера кнопок из SDL, левая — 1, а не 0 как в GLFW: первые сборки кнопки из-за этого не нажимались вовсе). Проверено режимом `LL_COVERAGE=y` на сетапе игрока (его моды и конфиги) настоящими событиями SDL. Итог считается по инвентарю после ответа сервера и показывается над хотбаром всегда (и в чат, если сообщения включены): что перенесено, «переносить нечего», «инвентарь заполнен», «сервер не принял перенос»; строка итога пишется и в лог игры. Тумблер «Кнопка „Перенести всё необходимое“» (`hudTransferButton`, вкл).

**new — «Скрывать блоки без опоры».** Тумблер на вкладке «Подсветка» (`ghostHideUnsupported`, выкл): голограмма блока, которому в мире сейчас не на чем держаться — подвесная табличка без потолка и стены, светящиеся ягоды и бледный свисающий мох без блока сверху, лиана в воздухе, факел без пола, — не рисуется вовсе (ни модель, ни оверлей). Решает ванильный `canSurvive` по миру игрока прямо в сборке чанка; цепочка одного рода (ягоды под ягодами, верх двери над низом) держится, если держится её начало. Опору поставили — голограмма появляется, соседний чанк через границу пересобирается тоже. Проверка `Ctrl+B` такие клетки тоже не показывает и не считает в «пропущено» (поставили опору — рамка появляется сама).

**new — «Опора с соседнего слоя».** Тумблер там же (`ghostSupportFromLayers`, выкл), для режимов со слоями: если блоку видимого слоя не на чем держаться, а держит его блок схемы на соседнем слое (табличка на слое N, потолок на N+1), этот блок показывается со слоем и ставится Easy Place — для прицела Litematica клетка считается «внутри слоя» (`LayerRange.isPositionWithinRange`), голограмму дорисовывает сборка чанка. Опорой считается только блок, с которым висящий и правда устоит; набор пересчитывается при смене слоя, схемы и блоков мира.

Проверено: новый режим `LL_COVERAGE=u` — эталон с табличкой, ягодами в два звена, мхом и лианой под потолком: тумблер выкл — убрано 0; вкл — ровно пять висящих клеток, камень и факел на полу остаются; потолок поставлен — убрано 0; один слой без потолка — четыре опоры «внутри слоя», дорисованы, Easy Place в выживании ставит опору, она уходит из списка; в настоящем сундуке кнопка вровень с верхом окна, панель под ней, щелчок забрал камень, таблички и ягоды, землю не тронул. Новый режим `LL_COVERAGE=v` — столбик досок (нет в инвентаре) вплотную перед камнем (есть): режим выкл — луч упирается в доски; вкл — внутренние грани включились, луч проходит доски и попадает в камень, Easy Place в выживании ставит камень, доски не тронуты, на снимке у непрозрачной голограммы камня видна грань к скрытым доскам; режим выкл — грани вернулись, включённые игроком остались. Регрессия: без Easy Place Fix+ — `g` 3160/0, `o` (с Zone Focus), `u`, `v`, тур `i`, `q`, `r` 89/0 (в общем прогоне один промах наблюдателя, отдельно 89/0), раньше в этой версии — `p`, `x`, `z`; с Easy Place Fix+ 1.0.15 — `g` 3160/0, `o`, `x`, `r` 89/0, `j` 168/0, `u`, `v`.

**EN**

**fix — in "only blocks from inventory" mode a visible hologram could not be placed through an invisible one.** Litematica's schematic ray trace and the aim assist now pass through holograms the mod does not draw (inventory mode, "hide blocks without support").

**fix — holes in holograms next to hidden blocks.** A face of a visible hologram is no longer culled against a schematic neighbour the mod hides.

**new — "Only blocks from inventory" turns on Ghost Alpha inner sides** and restores them when the mode is turned off.

**new — "Move everything needed" button in open containers.** Above the panel next to a chest, barrel, shulker box or any storage container (not the player inventory), its top level with the container window and never above it. One click shift-clicks out every stack of the current panel page that is still short (missing minus carried); data items only with the same pattern. Toggle `hudTransferButton` (on).

**new — "Hide blocks without support".** Toggle `ghostHideUnsupported` (off): holograms of blocks that have nothing to hold them in the world right now (hanging sign without a ceiling, glow berries and pale hanging moss with nothing above, a vine in the air) are not drawn; a chain of the same kind counts as held when its first link is.

**new — "Support from the next layer".** Toggle `ghostSupportFromLayers` (off): when a block of the visible layer hangs on a schematic block in the next layer, that support block is shown with the layer and can be placed with Easy Place although it is outside the layer.

## 1.0.3+26.3 — 08.10.2026 (повторный номер: первая 1.0.3 выходила и была снята в тот же вечер)

**RU**

**new — Ctrl+B поверх «Только блоки из инвентаря».** Когда режим «голограммы по инвентарю» включён, проверка `Ctrl+B` показывает только клетки, блок для которых есть в инвентаре: «пропущено», «не тот блок» и «не то состояние» — по предмету блока из схемы (пустой горшок под цветок — по цветку), «лишних» в режиме нет (там ничего ставить не надо); сводка начинается с «из инвентаря:». Фильтр стоит поверх найденного — без пересканирования: сменился набор предметов или режим (`InventoryGhosts.version`) — рамки и счётчики пересобираются в следующем тике. Режим выключен — `Ctrl+B` как прежде.

**fix — в режиме «только блоки из инвентаря» показывались цветы и горшки, которых нет в инвентаре.** Litematica не называет предмет для верхней половины двери, высокого цветка (сирень, пион, подсолнух) и изголовья кровати — считает его по нижней половине, а горшку с растением отдаёт само растение. Клетка «без предмета» показывалась всегда. Теперь: горшок с растением на пустом месте требует цветочный горшок (он ставится первым), а где Litematica предмета не называет — предмет самого блока. Всегда показываются только клетки, у которых предмета нет вовсе (вода без источника, огонь).

**fix — баннер с узором не доставался из инвентаря, если у него есть имя или описание.** Litematica ищет предмет ванильным `findSlotMatchingItem` — по совпадению всех компонентов, а мод требует только узор: баннер с тем же узором, но с именем (с сервера, из сундука клана) в руку не вставал, и клик не уходил. Теперь мод отдаёт Litematica копию предмета игрока с нужным узором (`ItemData.owned`) — поиск и выбор руки срабатывают. То же у горшков с черепками и голов. Перенесено в Easy Place Fix+ 1.0.15.

Проверено: `LL_COVERAGE=o` на «Портале» — без режима 11610 «пропущено», режим и пустой инвентарь — 0, с мхом — 1006, каждая рамка всех трёх категорий под мох, «лишних» 0, «не тот блок» 18 → 1; верх сирени и двери, горшок с одуванчиком без инвентаря скрыты, вода без источника видна; в зоне Zone Focus 5436 → 429; режим выключен — снова 11610 без перезапуска. Новый режим `x` (баннер из рюкзака: точно такой же и с именем) — до правки второй не ставился, после — оба.

**EN**

**new — Ctrl+B on top of "Only blocks from inventory".** With the mode on, Ctrl+B shows only cells whose schematic block is in the inventory (missing, wrong block, wrong state); extra blocks are not shown; the summary starts with "from inventory:". Markers rebuild on inventory or mode change without rescanning. Mode off — Ctrl+B as before.

**fix — flowers and pots showed in inventory mode without being in the inventory.** Upper halves of doors and tall flowers and bed heads have no item in Litematica, and a potted plant asked for the plant. A potted plant on an empty cell now needs a flower pot, and an unnamed item falls back to the block's own item.

**fix — a patterned banner was not taken from the inventory when it had a name or lore.** Litematica matches all item components; the mod now hands it a copy of the player's banner with the required pattern.

## 1.0.2+26.3 — 08.10.2026 (вторая выкладка номера: первые 1.0.2 и 1.0.3 сняты в тот же вечер, их правки — здесь)

**RU**

**fix — фриз при открытии и закрытии сундука.** Индекс хранилищ (`layerlist_storage.json`) переписывался целиком прямо в игровом потоке при открытии сундука и при каждом закрытии — даже если ничего не изменилось и поиск по хранилищам выключен. Индекс копится годами: у игрока 3,7 тыс. сундуков, 1,3 МБ — запись занимала 15–38 мс на Linux и больше на Windows с проверкой файлов антивирусом. Теперь игровой поток только снимает копию записей, файл пишет свой поток (подряд идущие сохранения склеиваются), без изменений файл не трогается, JSON без отступов (1,05 МБ вместо 1,33). При выходе из игры запись дожидается окончания. Замер режимом `LL_COVERAGE=z` на индексе игрока: `save()` в игровом потоке — 0,2 мс вместо 15 (худшее 0,6 вместо 38), худший тик при открытии и закрытии сундука — на уровне фона (28/27 мс при фоне 27; было 38/35).

**fix — панель в контейнере закрывала подсказку предмета.** InfoHud Litematica зовёт панель из хвоста `Gui.extractRenderState` — после экрана и его отложенной подсказки (`extractDeferredElements`), поэтому панель ложилась поверх подсказки. Теперь в открытом контейнере панель рисует сам экран — в `AbstractContainerScreen.extractRenderState` перед `extractCarriedItem`: над слотами, под предметом на курсоре и под подсказкой; сдвиги и выравнивание берутся у последнего вызова InfoHud, а InfoHud в этом кадре панель пропускает. Экран-наследник без этого вызова ничего не отметит — тогда панель рисует InfoHud, как раньше. Проверено: снимок режима `p` (сундук с предметами, курсор на крайнем слоте — подсказка поверх панели), тур `i` — 25/25.

**fix — растение над пустым горшком рисовалось мимо горшка.** Голограмма растения рисовалась его собственной моделью: кактус — целым блоком, цветок — полноразмерным крестом со случайным сдвигом, как на земле. Теперь рисуется модель «горшок с растением» из схемы без граней горшка и земли (`PotPlantRender`, отсев по спрайтам `block/flower_pot` и `block/dirt` в `BlockModelRendererSchematic.tessellateQuad`): растение стоит в горшке по центру, того размера, что после посадки, а грани горшка не ложатся на настоящий. Проверено `LL_COVERAGE=q` (одуванчик и кактус над пустыми горшками, снимок).

**EN**

**fix — freezes when opening and closing chests.** The storage index (`layerlist_storage.json`) was rewritten in full on the game thread when a chest was opened and on every close, even with nothing changed and storage search off. The index grows for years (3.7k chests, 1.3 MB for this player), so each write took 15–38 ms on Linux and more on Windows with antivirus scanning. Now the game thread only snapshots the records and a background thread writes the file (back-to-back saves are merged), unchanged data is not written, and the JSON is compact (1.05 MB instead of 1.33). Quitting the game waits for the write. Measured with `LL_COVERAGE=z` on the player's index: `save()` on the game thread 0.2 ms instead of 15 (worst 0.6 instead of 38), worst tick on chest open/close at background level (28/27 ms vs 27; was 38/35).

**fix — the panel covered item tooltips in containers.** Litematica's InfoHud draws the panel at the tail of `Gui.extractRenderState`, after the screen and its deferred tooltip. With a container open the screen now draws the panel itself, before the carried item and the tooltip, using the offsets of the last InfoHud call; InfoHud skips it for that frame.

**fix — the plant over an empty pot was drawn beside the pot.** The plant's own model was used: a whole cactus block, a full-size flower with the random ground offset. Now the schematic's potted model is drawn without the pot and soil faces, so the plant sits centred in the pot at its planted size and no pot faces overlap the real pot.

## 1.0.1+26.3 — 08.10.2026

**RU**

**new — оформление таблиц перед выгрузкой.** Кнопки **Excel**, **Google Таблицы** и «Отправить в таблицу команды» теперь сначала открывают окно «Оформление таблицы»: карточки палитр с образцом листа в их цветах — **светлая серая** (по умолчанию) и **тёмная фиолетовая** (прежняя тема, как у PNG-карточки). Клик по карточке выбирает, двойной клик — выбирает и сразу выгружает, «Выгрузить: Excel» стоит на Enter. Выбор запоминается: в следующий раз хватит двух Enter подряд. Та же настройка — «Оформление таблиц» в шестерёнке вкладки «Схемы». Светлая — те же роли листа, но поверхности и шапки серые (графит с белым), акценты фиолетовые (полоса заголовка, итоги, подсказки, пунктир полей) и золотые (подписи на тёмном, заголовок рамки «Кто собирает — впишите ники»); статусы — светлые зелёный и янтарный, ники — те же 16 тонов. Палитра действует на Excel, файл для импорта в Google и лист в таблице команды — для последнего нужен обновлённый скрипт (скопировать заново из окна выгрузки; старый скрипт палитру не читает и красит как раньше). Проверено режимом харнесса `LL_COVERAGE=p`: три выгрузки с проверкой xlsx и заливок, плюс путь через окно — Excel открывает оформление, клик по карточке меняет палитру, Enter выгружает в ней; скрипт — на заглушке Apps Script (с палитрой и без).

**new — сторона панели у контейнера.** Тумблер «Справа от окна» под «Рядом с открытым контейнером»: по умолчанию панель теперь встаёт **справа** от окна сундука (раньше — слева); выключен — слева, как было. Если со своей стороны тесно, панель переходит на другую, и только потом ужимается. Пример: в инвентаре с открытой книгой рецептов окно сдвинуто вправо, и справа на панель не хватает места — она встаёт слева.

**Настройки мода по умолчанию на весь экран.** Новое умолчание «Настройки на весь экран» — вкл. У игроков, у которых настройки уже сохранены, остаётся их прежнее значение.

**new — «Голограммы по инвентарю».** Тумблер «Только блоки из инвентаря» (вкладка «Подсветка», клавиша «Голограммы по инвентарю вкл/выкл» — по умолчанию не назначена): в схеме остаются только те блоки, предмет для которых лежит у вас в хотбаре, рюкзаке или второй руке. Фильтр стоит в сборке чанка Litematica, то есть поверх слоёв и обрезки Zone Focus: видимый слой или зона по-прежнему решают, что вообще рисуется, а режим убирает из этого лишнее. Подобрали или выложили стак — схема пересобирается сама (только если это меняет картину). Блоки без предмета (вода, огонь) остаются. `Ctrl+B` сверяет мир со схемой, а не с мешем, и режимом не затрагивается: «пропущено» считается по всем блокам. Проверено в игре самотестом `LL_COVERAGE=o`: пустой инвентарь, один предмет, 36 предметов, один слой, `Ctrl+B` с режимом и без.

**fix — поиск по хранилищам записывал содержимое сундука на другой сундук.** Клик по сундуку вставал в очередь, а открывшийся экран забирал из неё *самый старый* клик. Если перед этим был клик, на который сундук не открылся (заперт привата, придавлен блоком, клик погасил Easy Place) или повторный клик по тому же сундуку (зажатая ПКМ при заметном пинге), экран следующего сундука записывался на чужую позицию. Отсюда все три симптома из отзыва: отметка на сундуке, где блока нет; «открыл всё — нигде нет, переоткрываю — нашлось»; отметка пропадает при открытии и возвращается при повторном. Теперь в очередь идёт только клик, который действительно ушёл на сервер; повторный клик по тому же сундуку не дублируется; экран берёт последний клик, а если два разных контейнера кликнуты быстрее ответа сервера — не записывается вовсе (лучше не записать, чем записать не туда). Пустое меню, пока сервер не прислал слоты, тоже больше не записывается. Проверено самотестом `LL_COVERAGE=q`: на старой логике он воспроизводит подмену, на новой — нет. Записи, испорченные раньше, исправятся при следующем открытии сундука.

**fix — провод-точка вставал крестом.** Одиночный редстоуновый провод из руки встаёт крестом (на все четыре стороны), а точкой становится только от клика рукой — Easy Place этот клик не делал: для сверки разница выглядела как «соединения досчитаются сами», и клетка считалась готовой. Схема с точками после Easy Place работала иначе: крест запитывает блоки сбоку (поршень, лампу, механизм рядом), точка — нет. Теперь точка из схемы доводится кликом сразу после установки (как задержка повторителя), обратный случай — тоже. Проверено новым режимом харнесса `LL_COVERAGE=r` (`DumpRedstone`): редстоун-схема из 44 клеток — рычаг → провод → повторитель → компаратор → лампа, подъём провода на блок, угол, тройник, точки на полу и на блоке, факелы на полу и на стене, повторители на 4 стороны с задержками 1–4, компараторы в обоих режимах, наблюдатели, поршни, кнопки, рычаги, воронка в выбрасыватель, нотный блок, датчик света, мишень — через весь конвейер Easy Place в выживании, протокол выключен. До правки неверны 2 клетки из 44 (обе точки), после — 0, лишних блоков нет.

**fix — пустой горшок там, где нужен горшок с цветком.** Раньше такая клетка считалась «не тем блоком»: при «Только пустые места» цветок пропадал из списка материалов, Litematica прятала голограмму и красила горшок красным, `Ctrl+B` писал «не тот блок». Теперь клетка недостроенная: в списке нужен цветок, а горшок уже готов; над горшком видна голограмма цветка, оверлей «не хватает» вместо красного; `Ctrl+B` — «пропущено». Горшок с *другим* цветком по-прежнему «не тот блок». Режим «голограммы по инвентарю» для такой клетки смотрит на цветок.

## 1.0.0+26.3 (публичный выпуск; внутренние 0.0.26 и 0.0.27 слиты в него)

**RU**

**new — замена блока прямо в «Материалах».** Клик по названию строки (или «Заменить блок…» в меню правой кнопки) открывает окно с поиском по названию и id; список начинается с похожих блоков (доски — к доскам). Enter или «Заменить» — и все такие блоки схемы становятся выбранным, в том числе в голограммах всех её размещений; Easy Place ставит уже новый блок, список материалов пересчитывается. Направление, половинка, вода и другие совпадающие свойства сохраняются. У заменённой строки — пометка «← Дубовые доски» и кнопка отката. Файл схемы на диске не меняется: замены лежат в `config/layerlist_replacements.json` с отпечатком файла и накладываются при каждом чтении схемы (вход в мир, смена измерения, «Загрузить» в Litematica), так что переживают перезапуск игры. Замена переписывает палитру, а не клетки, поэтому на схеме в 41 млн блоков она занимает миллисекунды. Нельзя заменять сущности, предметы, не являющиеся блоками, блоки внутри других (растение в горшке), строки «Списка HUD» (он общий для всех размещений — откройте схему) и схему без файла; кнопка серая, подсказка называет причину. Проверено в игре на английском и русском при GUI 2 и 3, включая большую схему.

**«Призраки» в текстах названы голограммами, как в Litematica.** В русском переводе самой Litematica блоки схемы называются голограммами («Размещённые голограммы» в её главном меню), и мод теперь говорит так же: настройки и подсказки (прозрачность голограмм, тумблер «Голограммы за мышью», сущности в Easy Place), документация и журнал изменений. Ключи языка и опции не менялись — настройки не сбрасываются.

**Внутреннее (тексты)**

- Комментарии в коде и строки харнесса (`[look]`, `[entities]`, шаги `k`) тоже говорят «голограмма»; разборщиков логов, завязанных на прежние слова, нет. Карта модулей — `docs/MODULES.md`, журнал.

**fix — по отзыву игрока после первого знакомства с вкладкой «Схемы».**

- Буквы снова набираются во всех полях вкладки «Схемы» и в поиске настроек мода: поиск в «Библиотеке» и «Материалах», «Кто собирает» и название таблицы в окне выгрузки, адрес и ключ таблицы команды в настройках вкладки, X/Y/Z в редакторе размещения. В 26.3 игра получает буквы, только пока у окна включён ввод текста, а поля мода его не включали: стирать и двигать курсор было можно, печатать — нет. Снова работает и «начните печатать в списке — откроется поиск». Кириллица, Ctrl+A, Tab между полями, Enter и Esc — как раньше; при вводе через IME (китайский, японский) окно подсказок встаёт у курсора, а набираемое слово видно под полем (в поиске настроек — только готовый текст).
- «+ игроки онлайн» в «Кто собирает» больше не вписывает весь сервер разом, а открывает выбор: ники игроков онлайн плашками с «+», свой ник первым, остальные по алфавиту. Клик добавляет игрока, уже добавленные отмечены галочкой; фильтр по нику (Enter добавляет единственного найденного), «Добавить всех (N)» — всех, кого видно после фильтра; «Готово» или Esc — назад к окну выгрузки.
- «Кто собирает» объясняет себя: под заголовком — две строки о том, что в таблице ники станут выпадающим списком и по ним посчитается «Кто сколько собрал», а вписывать их необязательно — ники добавляются и в самой таблице. Подробнее — в подсказке заголовка.
- Таблица сбора: справа от списка — рамка «Кто собирает — впишите ники» (16 полей, при большом списке игроков — больше). Ники, вписанные туда прямо в Excel или Google Таблицах, сразу появляются в выпадающем списке «Кто собирает», получают свой цвет, строку «Кто сколько собрал» и полосу на диаграмме. Пока ников нет или никто не выбран, над диаграммой написано, что делать. Проверено настоящим импортом в Google Таблицы.
- Кнопка в главном меню Litematica теперь называется «Material HUD» и открывает настройки мода: «← Меню Litematica» и Esc возвращают в меню, «Схемы» — вкладка «Схемы ›» в самих настройках. Опция — «Кнопка Material HUD в меню Litematica».
- Discord в окне выгрузки — цветом Discord, как Excel зелёным и Google Таблицы синим. Если текст не влез в одно сообщение, «Скопировать следующую часть» горит золотом, пока части остаются, а в конце становится серой «Все части скопированы»; «Discord: скопирована часть N из M» пишется только в строке Discord — уведомления больше не складываются стопкой поверх «Закрыть».
- Редактор размещения: тумблер «Голограммы на лету» стал «Голограммы за мышью», под ним — «Только пока тащите схему ЛКМ». У схемы больше 200 000 блоков тумблер выключен, и пометка объясняет почему: голограммы ждут, пока отпустите кнопку. Исправлено: если мышь замирала с зажатой кнопкой, голограммы отставали от рамки до следующего движения — теперь догоняют её сами.
- Файлы выгрузок называются по схеме: «Дворец Мнений.xlsx» вместо «Дворец Мнений-2026-10-07_14-05.xlsx» или «Список HUD-…». Google Таблицы называют импортированную таблицу по имени файла — значит, и там таблица теперь называется по схеме. У «Списка HUD» имя — по схемам включённых размещений («Дом» или «Дом + ещё 2»), у размещения — по его схеме, даже если само размещение переименовано. Знаки, которые Windows не разрешает в именах файлов, убираются, слишком длинное имя укорачивается по слову. Если файл с таким именем уже есть, новый получает «(2)», «(3)»… — прежний не затирается. «Картинка» ложится своей папкой по схеме — «Дом/1.png», «Дом/2.png»… (одна страница — тоже «Дом/1.png»), повтор — новая папка «Дом (2)». Название листа в таблице, как и раньше, — из поля «Название таблицы».
- Кнопка «Папка с выгрузками» — в окне выгрузки рядом с «Закрыть» и в подвале вкладки «Материалы»: открывает папку с файлами и выделяет последний выгруженный (до первой выгрузки — просто папку выгрузок). После каждой выгрузки в файл кнопка загорается золотым и плавно гаснет примерно за 30 секунд — видно, куда идти за таблицей. В тесном подвале она сворачивается до значка папки.
- Итог выгрузки — прямо в окне выгрузки, внизу, а не всплывающим сообщением: путь к файлу и кнопки «Открыть файл», «Показать в папке» (для Google Таблиц — ещё «Открыть Google Таблицы», для таблицы команды — «Открыть лист»), ошибка — там же красным; окно само прокручивается к этой строке. Сообщение в углу экрана больше не закрывает «Закрыть» и «Папку с выгрузками» (она по-прежнему загорается золотом). Если закрыть окно, пока файл ещё пишется, итог, как и раньше, придёт сообщением над подвалом экрана. «Вернуть» после «Очистить» в «Кто собирает» — тоже в окне, прямо под полем.
- Формат «Копировать» в окне выгрузки и в подвале «Материалов» теперь называется «Экспортировать», а подсказка объясняет, что он делает: копирует таблицу в буфер обмена — вставьте её в любую таблицу (Ctrl+V). Работает как раньше.
- Под «Всё / Только недостающее» в окне выгрузки написано, что именно попадёт в список. У «Списка HUD» «Только недостающее» — пустые места размещений; поставленные неправильно (не тот блок или не то состояние) туда не входят — их сначала нужно сломать (так работает настройка «Только пустые места»; выключите её — войдут). У схемы-файла сверки с миром нет, поэтому «Только недостающее» — это вся постройка. Инвентарь и сундуки выгрузка не вычитает — подсказка переключателя раньше говорила иначе, исправлено.
- «Схема» во вкладке «Материалы» больше не серая, если в «Библиотеке» ничего не выбрано, а в мире есть схема: берётся выбранное в Litematica размещение, иначе единственное размещение (или загруженная в Litematica схема); если схем несколько — «Схема ▾» открывает список. Серой она остаётся, только когда схемы нет вовсе, и подсказка говорит, что сделать.
- Кнопка «Выгрузить ▾» в карточке схемы называется «Экспорт списка материалов ▾»; где не влезает (узкая карточка) — «Экспорт материалов ▾».
- Схемы можно удалять прямо в «Библиотеке»: «Удалить…» в меню правой кнопки (и клавиша Delete). Файл уходит в «Корзину» и хранится там 30 дней, сразу после удаления его можно вернуть кнопкой «Отменить». Если схема размещена в мире, окно предупредит: размещения останутся до смены измерения или следующего входа в мир, а вторая кнопка — «Удалить и убрать размещения».
- Вкладка «Корзина» — рядом с «Библиотекой», «Размещениями» и «Материалами» (на вкладке видно число, строка «Корзина (N)» внизу «Библиотеки» тоже ведёт на неё): откуда удалён файл, когда, сколько дней осталось; «Восстановить» (в прежнюю папку, а если имя занято — «Имя (2)»), «Удалить навсегда», «Очистить корзину», «Открыть папку». Через 30 дней файл удаляется сам.
- «Выбрать» в «Размещениях» отменяется: на выбранном размещении кнопка называется «Снять выбор».
- Клавиша «Открыть меню «Схемы»» — первой в разделе «Экраны» вкладки «Клавиши». По умолчанию ничего не назначено.
- Выгрузка «Картинка»: рядом со страницами 1.png, 2.png… в папке схемы лежит превью.png — постройка в изометрии в фиолетовой рамке с названием схемы, размерами, числом блоков и автором. Большие схемы рисуются упрощённо (это написано на постере), у битого файла — аккуратная заглушка. У «Списка HUD» превью нет: это не одна схема.
- Флажки вкладки «Схемы» стали тумблерами, как в остальных настройках: «во всех папках», «Только недостающее», «Иконки предметов», «Крупное сырьё», «Диаграмма», размещения в карточке схемы и колонки «Вкл» и «Отрисовка» в «Размещениях».
- Редактор размещения: под полями X, Y, Z — кнопки «−» и «+». Клик сдвигает схему на «Шаг», а если кнопку зажать, сдвиг повторяется всё быстрее. Вся серия отменяется одним «Отменить шаг», а голограммы большой схемы не перестраиваются на каждом шаге.
- Схему можно поднять или опустить, потянув за угол голограммы левой кнопкой: углы рамки отмечены кубиками, под мышью угол становится золотым.
- Облёт вокруг схемы: правая кнопка мыши вращает вид вокруг неё, колесо приближает и отдаляет, средняя кнопка (или Shift + правая) сдвигает вид. F показывает схему целиком, O включает облёт, T — вид сверху. Кнопка вида в панели переключает «Обычный», «Облёт» и «Сверху».
- Тумблер «Голограммы едут за мышью» называется «Голограммы за мышью»: прежнее имя не влезало в панель.
- Настройка «Качество превью» стала «Отображением схемы»: «Оптимизировано» — большие схемы кубами, быстро (как раньше); «Качественно» — вся схема честными блоками, пока хватает памяти игры. Перед включением «Качественно» мод предупреждает, что на больших схемах может просесть FPS и уйти много памяти.
- Кнопка настроек в шапке экрана «Схемы» — надписью «Настройки», а не шестерёнкой (в самом узком окне — снова значок).

**Внутреннее**

- `ui/kit/TextFocus` — включает и выключает ввод текста SDL для своих полей через ванильный `TextInputManager` (`startTextInput`/`stopTextInput` с владельцем), ставит окно IME к курсору (`setTextInputArea`) и рисует черновик IME ванильной плашкой. Через него идут `TextInput`, поля X/Y/Z редактора, экран «Схемы» («буква в списке — поиск») и экран настроек мода; `Modal` передаёт ввод по Tab из рук в руки.
- Харнесс, режим `w` (`DumpTyping`): печатает во все свои поля через настоящий вход событий SDL (`MouseHandler`, `KeyboardHandler.keyPress/textInput/textEditing`) и у каждого поля проверяет состояние ввода текста игры и SDL; под X11 — сквозная проверка настоящими клавишами (XTest). 64 OK / 0 FAIL; контрольный прогон без включения ввода воспроизвёл жалобу игрока: ванильный чат буквы получает, поиск мода — нет. Режим `k` печатал мимо SDL и этого не видел.
- Харнесс, режим `k`: `KDialog` — окно выгрузки, выбор игроков, фильтр, «Добавить всех», Discord частями, кнопка «Material HUD» в меню Litematica; тумблер голограмм — в `KPlace`. 344 OK / 0 FAIL на английском и русском, 20 новых снимков при GUI 2 и 3.
- Рамка ников в таблице проверена и без игры: XSD всех частей, проверки рамки и формул с никами, вписанными после выгрузки, — 2 412 ячеек, 0 расхождений.
- Новые точки контакта с игрой и malilib — `TextInputManager`, `IMEPreeditOverlay`, `GuiContext.getGuiGraphics()`, `preeditUpdated`, `Minecraft.getUser()` (карта модулей `docs/MODULES.md`, «Сводка»: 799 контактов, 244 тихих). Цвет `Theme.DISCORD`, значок `Glyphs.PLUS`.
- Имена выгрузок: `gui/export/ExportNames` — имя файла по источнику (файл «Библиотеки», размещение, «Список HUD»); `ExportRequest.fileName` и `MaterialsExport.fileName` — новые компоненты (прежние конструкторы оставлены); `ExportFiles` — очистка имени под Windows (запрещённые и управляющие знаки, края, имена устройств, ≤ 100 знаков по слову) и «(2)» вместо даты в имени; картинка — своя папка `<схема>/` со страницами `1.png`, `2.png`…. Кнопка папки — `gui/export/ExportsFolder` (общее на игру время последней выгрузки по `Util.getMillis()`, ease-out за 30 с), `Exporters.openFolder`. Новые точки контакта — Litematica `SchematicPlacement.isEnabled/getSchematicFile/getName` и свой `PlacementOps.all()` («Сводка» после имён: 801 контакт, 244 тихих).
- Харнесс, режим `k`: `KSources` — пояснения фильтра у схемы и у списка HUD; на размещении клетка каменных кирпичей заменена землёй: «не тот блок» +1, «не хватает» −1 при включённой «Только пустые места» и прежнее при выключенной, выгрузка берёт то же число; «Схема» без схем, при одном и двух размещениях и при выбранном в Litematica; кнопка карточки при GUI 2 и 3 и в узких окнах. `SchematicChoice` (кандидаты «Схемы»); новые точки контакта — Litematica `getSelectedSchematicPlacement`, `SchematicHolder.getAllSchematics`, `LitematicaSchematic.getFile`, malilib `ConfigBoolean.getBooleanValue` («Сводка»: 804 контакта, 245 тихих).
- Харнесс, режим `k`: `KNaming` — имена файлов у каждого источника (схема, «Список HUD» без размещений, с одним, с двумя размещениями одной схемы и двух схем, переименованное размещение), «(2)» и «(3)» без затирания, картинка папкой (`1.png`, `2.png`; занятая папка — следующее имя), 13 случаев очистки (запрещённые знаки, CON/nul.backup/LPT9, длинное имя, эмодзи, коды `§`), кнопка папки в обоих подвалах при GUI 2 и 3 (видна, в окне, ни на что не легла), свечение: сразу после выгрузки ≈ 1, через 15 с (сдвиг часов свечения) — 0,27, через 31 с — 0; снимки `folder-*`. Вместе с `KSources`: `k` 566 OK / 0 FAIL на английском и русском (было 344), `g` 2822 / 0.
- Корзина «Библиотеки»: `schematics/library/SchematicTrash` (`<папка игры>/material-hud-trash/`, `index.json`, 30 дней), окно `gui/DeleteFlow` и вкладка `gui/TrashPane` (сначала была окном `TrashDialog`); `PlacementOps.deselect/detach/reattach`. Постер: `preview/PreviewCapture` + `CaptureRenderer` (снимок модели вне экрана, на чёрном и белом фоне — прозрачность из разницы), `export/PreviewPoster` + `PosterPainter`; в выгрузку — через `ExportSource.entry()` и `ExportJob`. Новые точки контакта: 824 / 253 тихих в сводке `docs/MODULES.md` (M22 +3, M23 +14, M24 +4, M21 −1).
- Харнесс, режим `k`: `KTrash`, `KDeselect`, `KHotkey` (клавиша — событием SDL и настоящей клавишей X-сервера; `LL_K_ONLY=lib`), `KPoster` (постеры четырёх схем при открытом окне выгрузки и «Картинка» из окна → `превью.png` в папке схемы; `LL_K_ONLY=poster`).

**EN**

**new — replace a block right in "Materials".** Click a row's name (or "Replace block…" in the right-click menu) to open a picker with search by name and id; the list starts with similar blocks (planks near planks). Enter or "Replace" turns every such block of the schematic into the chosen one, in the holograms of all its placements too; Easy Place then places the new block and the material list recounts. Facing, half, waterlogging and other matching properties are kept. A replaced row shows "← Oak Planks" and a restore button. The schematic file on disk is not changed: replacements live in `config/layerlist_replacements.json` with a file fingerprint and are applied every time the schematic is read (joining a world, changing dimension, "Load" in Litematica), so they survive a restart. The replacement rewrites the palette, not the cells, so on a 41-million-block schematic it takes milliseconds. Entities, non-block items, blocks that are part of another block (a plant in a pot), "HUD list" rows (it merges all placements — open the schematic) and a schematic without a file can't be replaced; the button is greyed and the tooltip names the reason. Checked in game in English and Russian at GUI 2 and 3, including the big schematic.

**Russian texts call schematic ghost blocks "голограммы" (holograms), as Litematica does.** Litematica's own Russian translation names them so ("Размещённые голограммы" in its main menu); the mod's Russian settings, tooltips, documentation and changelog now match. English texts, language keys and options are unchanged, so settings are kept.

**Internal (texts)**

- Code comments and harness log lines (`[look]`, `[entities]`, `k` steps) use the same word; no log parser depended on the old one. Module map — `docs/MODULES.md`, journal.

**fix — after the player's first go with the Schematics tab.**

- Typing letters works again in every field of the Schematics tab and in the mod's settings search: the Library and Materials search, "Who collects" and the sheet title in the export window, the team sheet address and key in the tab settings, X/Y/Z in the placement editor. In 26.3 the game only receives letters while the window has text input switched on, and the mod's fields never switched it on: Backspace and the arrow keys worked, typing did not. "Start typing in the list to search" works again too. Cyrillic, Ctrl+A, Tab between fields, Enter and Esc behave as before; with an IME (Chinese, Japanese) the candidate window sits at the caret and the word being composed shows under the field (the settings search gets only the finished text).
- "+ online players" in "Who collects" no longer adds the whole server at once; it opens a picker: online players as chips with "+", your own nick first, the rest alphabetically. A click adds a player, added ones are ticked; filter by nick (Enter adds the only match), "Add all (N)" adds everyone the filter shows; "Done" or Esc goes back to the export window.
- "Who collects" explains itself: two lines under the heading say that in the sheet the nicks become the "Collector" drop-down and "Who collected what" is counted by them, and that typing them in is optional — nicks can be added in the sheet as well. More in the heading tooltip.
- Collection sheet: a "Collectors — type nicks here" box next to the list (16 fields, more for a long roster). Nicks typed there right in Excel or Google Sheets show up in the "Collector" drop-down at once and get their colour, a "Who collected what" row and a bar in the chart. While there are no nicks or nobody is picked yet, a line above the chart says what to do. Checked with a real import into Google Sheets.
- The button in Litematica's main menu is now "Material HUD" and opens the mod settings: "← Litematica menu" and Esc go back to the menu, and the Schematics tab is the "Schematics ›" tab of the settings. The option is "Material HUD button in Litematica's menu".
- Discord in the export window is in Discord colour, as Excel is green and Google Sheets blue. When the text does not fit in one message, "Copy next part" glows gold while parts remain and turns into a grey "All parts copied" at the end; "Discord: copied part N of M" appears only in the Discord line — toasts no longer pile up over "Close".
- Placement editor: the "Live ghosts" toggle is now "Ghosts follow the mouse", with "Only while dragging with LMB" under it. For a schematic over 200,000 blocks the toggle is disabled and the note says why: the ghosts wait until you release the button. Fixed: when the mouse stopped with the button held, the ghosts lagged behind the frame until the next movement — now they catch up on their own.
- Export files are named after the schematic: "Palace.xlsx" instead of "Palace-2026-10-07_14-05.xlsx" or "HUD list-…". Google Sheets names an imported spreadsheet after the file, so the spreadsheet there is named after the schematic too. A "HUD list" export is named after the schematics of the enabled placements ("House" or "House + 2 more"), a placement export after its schematic even if the placement was renamed. Characters Windows does not allow in file names are dropped and an overlong name is shortened at a word. If a file with that name already exists, the new one gets "(2)", "(3)"… — the old file is never overwritten. "Picture" goes into its own folder named after the schematic — "House/1.png", "House/2.png"… (a single page is "House/1.png" too); exporting again makes a new folder "House (2)". The sheet name inside the table still comes from the "Sheet title" field.
- An "Exports folder" button — in the export window next to "Close" and in the footer of the Materials tab: it opens the folder with the files and selects the last exported one (before the first export, just the exports folder). After every export to a file the button lights up gold and slowly fades over about 30 seconds, so you can see where to go for the table. In a cramped footer it shrinks to a folder icon.
- The export result now shows inside the export window, at the bottom, instead of a pop-up: the file path with "Open file" and "Show in folder" buttons (for Google Sheets also "Open Google Sheets", for the team sheet "Open sheet"), an error in red in the same place; the window scrolls to that line by itself. The corner pop-up no longer covers "Close" and "Exports folder" (which still glows gold). If you close the window while the file is still being written, the result arrives as a pop-up above the screen footer, as before. "Undo" after "Clear" in "Who collects" is in the window too, right under the field.
- The "Copy" format in the export window and the Materials footer is now called "Export", and its tooltip says what it does: copies the table to the clipboard — paste it into any spreadsheet (Ctrl+V). It works as before.
- Under "All / Only missing" the export window now says exactly what goes into the list. For the "HUD list", "Only missing" is the empty spots of the placements; wrongly placed blocks (the wrong block or the wrong state) are not included — break them first (that is the "Only empty spots" setting; turn it off to include them). A schematic file is not compared with the world, so there "Only missing" is the whole build. The export never subtracts inventory or chests — the switch tooltip used to say otherwise, fixed.
- "Schematic" in the Materials tab is no longer greyed out when nothing is selected in Library but the world has a schematic: it takes the placement selected in Litematica, else the only placement (or a schematic loaded in Litematica); with several, "Schematic ▾" opens a list. It stays grey only when there is no schematic at all, and the tooltip says what to do.
- The "Export ▾" button of the schematic card is now "Export material list ▾"; where that does not fit (a narrow card) — "Export materials ▾".
- Schematics can be deleted right in Library: "Delete…" in the right-click menu (and the Delete key). The file goes to the "Trash" and stays there for 30 days; right after deleting, "Undo" brings it back. If the schematic is placed in the world, the dialog warns that the placements stay until the next dimension change or world join, and offers "Delete and remove placements".
- A "Trash" tab next to Library, Placements and Materials (it shows the count; the "Trash (N)" row at the bottom of the Library list leads to it too) shows where each file came from, when it was deleted and how many days are left; "Restore" (to the old folder, or "Name (2)" if the name is taken), "Delete forever", "Empty trash", "Open folder". After 30 days a file is deleted by itself.
- "Select" in Placements can be undone: on the selected placement the button reads "Deselect".
- The "Open the Schematics menu" key is first in the "Screens" section of the Keys tab. It is unbound by default.
- "Picture" export: next to the pages 1.png, 2.png… the schematic's folder now has превью.png ("preview") — the build in isometric view in a violet frame with the schematic's name, size, block count and author. Big schematics are drawn simplified (the poster says so); a broken file gets a neat placeholder. The HUD list has no preview, as it is not one schematic.
- The Schematics tab uses toggle switches instead of check boxes, like the rest of the settings: "in all folders", "Only missing", "Item icons", "Raw materials", "Chart", placements in the schematic card, and the "On" and "Render" columns in Placements.
- Placement editor: "−" and "+" buttons under the X, Y and Z fields. A click moves the schematic by one "Step"; hold the button and it keeps moving, faster and faster. The whole run undoes with one "Undo step", and a big schematic's ghost blocks are not rebuilt on every step.
- Drag a corner of the ghost frame with the left mouse button to raise or lower the schematic: the corners are marked with small cubes, and the one under the mouse turns gold.
- Orbit around the schematic: the right mouse button turns the view around it, the wheel zooms, the middle button (or Shift + right) pans. F frames the whole schematic, O toggles orbit, T is the top view. The view button in the panel cycles "Normal view", "Orbit" and "Top view".
- The "Preview quality" option is now "Schematic display": "Optimized" draws big schematics as cubes, fast (as before); "Quality" draws the whole schematic in real blocks while the game has memory for it. Before turning "Quality" on, the mod warns that FPS may drop and a lot of memory may be used on big schematics.
- The settings button in the Schematics screen header now reads "Settings" instead of a gear (a gear only in the narrowest window).

**Internal**

- `ui/kit/TextFocus` switches SDL text input on and off for our own fields through vanilla `TextInputManager` (`startTextInput`/`stopTextInput` with an owner), places the IME window at the caret (`setTextInputArea`) and draws the IME draft with the vanilla overlay. `TextInput`, the editor's X/Y/Z fields, the Schematics screen ("type in the list to search") and the mod settings screen all go through it; `Modal` hands input over on Tab.
- Harness mode `w` (`DumpTyping`): types into every field of ours through the real SDL event entry points (`MouseHandler`, `KeyboardHandler.keyPress/textInput/textEditing`) and checks the game's and SDL's text input state for each field; under X11 also an end-to-end check with real keys (XTest). 64 OK / 0 FAIL; a control run without switching input on reproduced the player's complaint: vanilla chat gets the letters, the mod's search does not. Mode `k` typed past SDL and could not see it.
- Harness mode `k`: `KDialog` — export window, player picker, filter, "Add all", Discord in parts, the "Material HUD" button in Litematica's menu; the ghost toggle in `KPlace`. 344 OK / 0 FAIL in English and Russian, 20 new screenshots at GUI 2 and 3.
- The nick box in the sheet was also checked without the game: XSD of every part, box and formula checks with nicks typed in after export — 2,412 cells, 0 mismatches.
- New contact points with the game and malilib — `TextInputManager`, `IMEPreeditOverlay`, `GuiContext.getGuiGraphics()`, `preeditUpdated`, `Minecraft.getUser()` (module map `docs/MODULES.md`, summary: 799 contacts, 244 silent). Colour `Theme.DISCORD`, glyph `Glyphs.PLUS`.
- Export names: `gui/export/ExportNames` picks the file name by source (Library file, placement, HUD list); `ExportRequest.fileName` and `MaterialsExport.fileName` are new components (the old constructors stay); `ExportFiles` cleans the name for Windows (forbidden and control characters, edges, device names, ≤ 100 characters at a word) and uses "(2)" instead of a date; a picture is its own folder `<schematic>/` with pages `1.png`, `2.png`…. The folder button is `gui/export/ExportsFolder` (one game-wide time of the last export by `Util.getMillis()`, ease-out over 30 s), plus `Exporters.openFolder`. New contact points — Litematica `SchematicPlacement.isEnabled/getSchematicFile/getName` and our own `PlacementOps.all()` (summary after the names: 801 contacts, 244 silent).
- Harness mode `k`: `KSources` — the filter explanations for a schematic and for the HUD list; on a placement one stone-brick cell is replaced with dirt: "wrong block" +1, "missing" −1 with "Only empty spots" on and unchanged with it off, and the export takes the same number; "Schematic" with no schematics, with one and two placements and with a placement selected in Litematica; the card button at GUI 2 and 3 and in narrow windows. `SchematicChoice` (the "Schematic" candidates); new contact points — Litematica `getSelectedSchematicPlacement`, `SchematicHolder.getAllSchematics`, `LitematicaSchematic.getFile`, malilib `ConfigBoolean.getBooleanValue` (summary: 804 contacts, 245 silent).
- Harness mode `k`: `KNaming` — file names for every source (schematic, HUD list with no placements, one, two placements of one schematic and of two schematics, a renamed placement), "(2)" and "(3)" without overwriting, the picture as a folder (`1.png`, `2.png`; a taken folder gets the next name), 13 cleaning cases (forbidden characters, CON/nul.backup/LPT9, a long name, emoji, `§` codes), the folder button in both footers at GUI 2 and 3 (visible, inside the window, overlapping nothing), the glow: ≈ 1 right after an export, 0.27 after 15 s (shifted glow clock), 0 after 31 s; screenshots `folder-*`. Together with `KSources`: `k` 566 OK / 0 FAIL in English and Russian (was 344), `g` 2822 / 0.
- Library trash: `schematics/library/SchematicTrash` (`<game dir>/material-hud-trash/`, `index.json`, 30 days), the `gui/DeleteFlow` window and the `gui/TrashPane` tab (at first a `TrashDialog` window); `PlacementOps.deselect/detach/reattach`. Poster: `preview/PreviewCapture` + `CaptureRenderer` (offscreen model shot on black and on white, alpha from the difference), `export/PreviewPoster` + `PosterPainter`; wired into export through `ExportSource.entry()` and `ExportJob`. New contact points: 824 / 253 silent in the `docs/MODULES.md` summary (M22 +3, M23 +14, M24 +4, M21 −1).
- Harness mode `k`: `KTrash`, `KDeselect`, `KHotkey` (the key as an SDL event and as a real X server key; `LL_K_ONLY=lib`), `KPoster` (posters of four schematics with the export window open, and "Picture" from the window → `превью.png` in the schematic's folder; `LL_K_ONLY=poster`).

## 0.0.26+26.3 (в работе, не выложено)

**RU**

**Новое — вкладка «Схемы».** Открывается кликом по вкладке «Схемы ›» в настройках, кнопкой «Схемы» в шапке настроек, хоткеем `openSchematics` (по умолчанию не назначен) и кнопкой «Схемы · Material HUD» в главном меню Litematica (её можно выключить). Настройки вкладки — шестерёнка в шапке экрана «Схемы». Вкладка проверена в игре (07.10.2026) на настоящих схемах игрока, самая большая — 857×274×891; что нашлось, исправлено (ниже).

- Библиотека: папка схем Litematica с подпапками, поиск по имени (в том числе во всех папках), сортировка, миниатюры у всех четырёх форматов (`.litematic`, `.schem`, `.nbt`, `.schematic`), карточка со сведениями (размер, блоки, автор, даты, версия данных, встроенное превью Litematica), перетаскивание файлов схем в окно. Большие схемы показываются упрощённо.
- 3D-превью: настоящие модели блоков с текстурами, виды «Изометрия», «Сверху», «Спереди», срез по высоте; левая кнопка вращает, правая двигает, колесо приближает к курсору. Границы панелей перетаскиваются.
- «Разместить здесь» ставит схему перед игроком и открывает редактор размещения в виде сверху. Схему тащат мышью по миру, крутят вокруг центра (R), отражают (M), ставят на землю (G) и на сетку чанков (C), двигают стрелками и полями X/Y/Z, отменяют шаги (Ctrl+Z/Y). «Отмена» (Esc) возвращает как было, «Готово» (Enter) оставляет.
- Вид сверху: камера над схемой, север вверху; правая кнопка — панорама, колесо — высота к курсору. На это время скрыты рука, прицел, туман дальности и (опция) облака.
- Подвкладка «Размещения»: все размещения мира — выбрать, двигать, открыть сверху, включить или выключить, удалить.
- Материалы схемы считаются так же, как HUD: флаги с узором, узорчатые вазы с черепками и головы игроков — отдельными строками, сущности (стойки для брони, рамки, картины) — по тем же правилам. «В инвентаре» — вместе с содержимым шалкеровых ящиков и мешков, «В сундуках» — по открытым вами хранилищам.
- Подвкладка «Материалы»: таблица с иконками предметов — всего, разбивка на шалкеровые ящики и стаки, в инвентаре, в сундуках, не хватает (зелёный — хватает, жёлтый — есть часть, красный — нет). Сортировка по любой колонке, поиск по названию и id, «Только недостающее», источник «Схема» или «Список HUD», итоги и кнопки выгрузки в подвале. ПКМ по строке — «Найти в сундуках» и копирование названия. F5 пересчитывает таблицу.
- Выгрузка списка материалов: таблица Excel «Таблица сбора» (ники и статусы «Собираю»/«Собрано» выпадающими списками, «Кто сколько собрал» с формулами и диаграммой, крупное сырьё по рецептам игры, иконки предметов), файл для Google Таблиц и отправка в таблицу команды (скрипт Apps Script в комплекте), CSV для Excel, копия в буфер, текст для Discord частями, картинка-карточка PNG, JSON, список для Litematica. Нигде нет TRUE/FALSE.
- Окно выгрузки: название таблицы, источник (схема целиком или список HUD) и «Только недостающее», список «Кто собирает» (ники вручную или все игроки онлайн, сохраняется между выгрузками), иконки предметов, крупное сырьё, диаграмма; Excel, Google Таблицы, CSV, копия в буфер, Discord частями, картинка, JSON, список для Litematica и «Отправить в таблицу команды». Большие таблицы с картинками пишутся в фоне — игра не замирает; после выгрузки — «Открыть файл» и «Показать в папке». Кнопка «Выгрузить ▾» в карточке схемы открывает меню форматов, кнопки в подвале «Материалов» выгружают ровно то, что видно в таблице.
- Иконки предметов в выгрузках (таблица Excel, Google Таблицы, таблица команды, картинка) — как в меню креатива: их рисует сама игра, поэтому объёмные блоки, сундуки, шалкеровые ящики, головы, флаги с узором, узорчатые вазы, щиты, костёр и наковальня выглядят так же, как в инвентаре.
- Таблица сбора для Excel и Google Таблиц — в тёмно-фиолетовой теме, как картинка-карточка: тёмный фон всего листа, светлый текст, фиолетовые рамки вместо серой сетки, «Собрано»/«Собираю» — тёмным зелёным и янтарным, диаграмма в цветах мода. Тема переживает импорт в Google Таблицы.

**fix — по итогам первой проверки вкладки в игре.**

- Панель HUD больше не рисуется поверх экрана «Схемы» и редактора размещения — она закрывала «Готово» и «Отмена».
- Посреди экрана больше не выскакивает «Scheduled task added…». Скрывая штатный HUD списка материалов Litematica, мод сам заводил список и подсчёт блоков для каждого нового размещения (в том числе из «Разместить здесь»); теперь он трогает только списки, которые уже есть. Остальные сообщения Litematica на экране «Схемы» приходят уведомлением в углу, в редакторе — строкой в панели.
- В редакторе размещения интерфейс игры скрыт, как по F1: панель быстрого доступа не торчит из-под подсказок, нет прицела, руки, виньетирования и обводки блока.
- Вид сверху держит схему в кадре: после сдвига стрелками, поворота (R), отражения (M), «К земле», сетки чанков и отмены камера догоняет схему, если та ушла под панель или за край окна.
- Перед загрузкой схемы в Litematica экран сначала показывает «Загрузка…», а не замирает без отклика. Само чтение большого файла по-прежнему занимает несколько секунд — столько же, сколько у кнопки «Загрузить» в Litematica.
- Отражение в панели редактора — коротко: «90° · фронт-тыл», влезает в ряд кнопок.
- Мелочи экрана «Схемы»: подсказка мыши превью — в строке статуса, а не поверх модели; «Срез 12/25» показывает слой; в карточке вместо «Unnamed» — имя файла; в узком окне одна «Разместить здесь» вместо двух; выбранный «Excel» в окне выгрузки больше не сливается с «Google Таблицами».

**fix (26.3) — левый клик во время ввода клавиши.** На вкладке «Клавиши» левый клик мимо поля во время ввода записывался в клавишу как кнопка мыши: в 26.3 у левой кнопки другой номер. Теперь он, как раньше, просто прерывает ввод. Общая библиотека aell-ui.

**Внутреннее — новый код вкладки сверен с чужим.** Весь код вкладки «Схемы» сравнён токенами с Litematica, malilib и всей ванилью 26.3: переноса логики нет; три места, похожие на чужие по форме (габарит миниатюры, коробка регионов, выделение и границы слова в поле ввода), переписаны. Отчёт — `docs/integration/SIMILARITY-0.0.26.md`.

**Внутреннее — шесть мест кода переписаны своими словами.** Проверка прав отметила шесть фрагментов, близких к чужому коду (Litematica, форк EasyPlaceFix, ванильный Minecraft). Все шесть написаны заново, поведение то же — проверено харнессом `t` байт в байт:

- выбор инструмента для копания (`ToolPick`, «Брать инструмент для копания»);
- обрезка подсчёта по слою (`LayerCountManager`) — теперь штатным `LayerRange.getClampedBox` из malilib;
- место панели HUD в раскладке InfoHud (`LayerHudRenderer`);
- отсечение маркеров оверлея в экономном режиме Ghost Alpha — теперь отсекает сама Litematica, мод подменяет только одну проверку опции (через MixinExtras, он уже входит в Fabric Loader);
- форма рельсов после установки (`Rails`) — свои правила вместо повторённого ванильного расчёта `RailState`;
- порядок сторон по взгляду при установке (`SimPlaceContext`) — свой порядок на тех же шагах вместо копии ванильного `Direction.orderedByNearest`.

Старый и новый код дали одинаковые дампы `t` на Litematica 0.29.1 и на 0.29.0: 1 222 760 порядков сторон, 990 001 форма рельсов, 396 288 обрезок, 31 800 мест панели, 4 752 000 решений отсечения, 928 выборов инструмента. Проверка контрактов `g` — 0 FAIL; дампы `b`, `c`, `e`, `i`, `j` совпали или отличаются только шумом (растения со случайным возрастом).

**Внутреннее — лицензия GPL-3.0-or-later везде.** `LICENSE` — полный текст GPLv3 (был GPLv2), `fabric.mod.json` — `GPL-3.0-or-later` (был `GPL-2.0-or-later`), `NOTICE.md` — та же лицензия вместо MIT, отметка «неофициальный аддон: не связан с авторами Litematica и malilib и не одобрен ими; не официальный продукт Minecraft» и источники идей функций.

**EN**

**New — the Schematics tab.** Opens from the "Schematics ›" tab in the settings, the "Schematics" button in the settings header, the `openSchematics` hotkey (unbound by default) and the "Schematics · Material HUD" button in the Litematica main menu (it can be turned off). The tab's own settings sit behind the gear in the Schematics screen header. The tab has been checked in game (07.10.2026) on real player schematics, the largest 857×274×891; what turned up is fixed (below).

- Library: the Litematica schematics folder with subfolders, search by name (including all folders), sorting, thumbnails for all four formats (`.litematic`, `.schem`, `.nbt`, `.schematic`), an info card (size, blocks, author, dates, data version, Litematica's embedded preview), and schematic files dropped onto the window are copied in. Large schematics are shown simplified.
- 3D preview: real block models with textures, "Isometric", "Top" and "Front" views, a height cut; left button rotates, right button pans, the wheel zooms towards the cursor. Panel borders can be dragged.
- "Place here" puts the schematic in front of the player and opens the placement editor in top view. Drag the schematic across the world with the mouse, rotate it around its centre (R), mirror it (M), drop it onto the ground (G) and onto the chunk grid (C), nudge it with the arrow keys and the X/Y/Z fields, undo steps (Ctrl+Z/Y). "Cancel" (Esc) restores it, "Done" (Enter) keeps it.
- Top view: the camera hovers over the schematic with north up; the right button pans, the wheel changes height towards the cursor. Meanwhile the hand, crosshair, distance fog and (optionally) clouds are hidden.
- "Placements" sub-tab: every placement in the world — select, move, open in top view, enable or disable, remove.
- Schematic materials are counted the same way as the HUD: banners with patterns, decorated pots with sherds and player heads get their own rows, entities (armor stands, item frames, paintings) follow the same rules. "In inventory" includes the contents of shulker boxes and bundles, "In chests" uses the storage you have opened.
- "Materials" sub-tab: a table with item icons — total, breakdown into shulker boxes and stacks, in inventory, in chests, missing (green — enough, yellow — some, red — none). Sort by any column, search by name or id, "Only missing", "Schematic" or "HUD list" source, totals and export buttons in the footer. Right-click a row to find it in chests or copy its name. F5 recounts the table.
- Material list export: an Excel "Collection sheet" (nick and "Collecting"/"Collected" drop-downs, a "Who collected what" block with formulas and a chart, raw materials from the game's recipes, item icons), a file for Google Sheets and sending to a team sheet (Apps Script included), CSV for Excel, a clipboard copy, Discord text in parts, a PNG card, JSON and a list for Litematica. No TRUE/FALSE anywhere.
- Export window: sheet title, source (whole schematic or the HUD list) and "Only missing", a "Who collects" roster (typed nicks or all online players, kept between exports), item icons, raw materials, chart; Excel, Google Sheets, CSV, clipboard copy, Discord in parts, picture, JSON, Litematica list and "Send to team sheet". Large sheets with pictures are written in the background so the game does not freeze; afterwards — "Open file" and "Show in folder". The schematic card's "Export ▾" button opens a format menu; the export buttons in the Materials footer export exactly what the table shows.
- Item icons in exports (the Excel sheet, Google Sheets, the team sheet, the picture) look like the creative menu: the game draws them itself, so 3D blocks, chests, shulker boxes, heads, banners with patterns, decorated pots, shields, campfires and anvils look the same as in the inventory.
- The collection sheet for Excel and Google Sheets uses the dark violet theme of the picture card: a dark background across the whole sheet, light text, violet borders instead of the grey grid, "Collected"/"Collecting" in dark green and amber, a chart in the mod's colours. The theme survives the import into Google Sheets.

**fix — after the first in-game check of the tab.**

- The HUD panel no longer draws over the Schematics screen and the placement editor — it covered "Done" and "Cancel".
- No more "Scheduled task added…" box in the middle of the screen. While hiding Litematica's own material list HUD, the mod created a material list and a block count for every new placement (including those from "Place here"); now it only touches lists that already exist. Other Litematica messages arrive as a toast in the corner on the Schematics screen and as a line in the panel in the editor.
- The placement editor hides the game HUD, as F1 does: no hotbar sticking out under the key hints, no crosshair, hand, vignette or block outline.
- Top view keeps the schematic in view: after nudging with the arrows, rotating (R), mirroring (M), "To ground", the chunk grid or undo, the camera follows the schematic if it slid under the panel or off the edge of the window.
- Before a schematic is loaded into Litematica the screen first shows "Loading…" instead of freezing with no response. Reading a large file still takes a few seconds — as long as Litematica's own "Load" button.
- The mirror readout in the editor panel is short — "90° · front-back" — and fits the button row.
- Smaller things on the Schematics screen: the preview mouse hint sits in the status line instead of over the model; "Cut 12/25" shows the layer; the card shows the file name instead of "Unnamed"; a narrow window has one "Place here" instead of two; the selected "Excel" in the export window no longer blends in with "Google Sheets".

**fix (26.3) — left click while capturing a key.** On the Hotkeys tab a left click outside the field during key capture was recorded as a mouse button: in 26.3 the left button has a different number. Now it just ends the capture, as before. Shared aell-ui library.

**Internal — the new tab code was checked against other code.** All of the Schematics tab was compared token by token with Litematica, malilib and the whole of vanilla 26.3: no logic was transplanted; three places that resembled other code in form (thumbnail extent, region box, selection and word edges in the text field) were rewritten. Report: `docs/integration/SIMILARITY-0.0.26.md`.

**Internal — six code fragments rewritten in our own form.** A rights audit flagged six fragments that stayed close to someone else's code (Litematica, the EasyPlaceFix fork, vanilla Minecraft). All six were written anew; behaviour is unchanged, verified byte for byte with harness mode `t`:

- picking the digging tool (`ToolPick`, "Pick the tool for digging");
- clipping the count to the layer range (`LayerCountManager`) — now through malilib's own `LayerRange.getClampedBox`;
- the HUD panel position in the InfoHud layout (`LayerHudRenderer`);
- overlay marker culling in the Ghost Alpha lean mode — Litematica now culls by itself, the mod only swaps one option check (via MixinExtras, which ships with Fabric Loader);
- the rail shape after placement (`Rails`) — own rules instead of the mirrored vanilla `RailState` calculation;
- the look-based direction order when placing (`SimPlaceContext`) — own ordering on the same float steps instead of a copy of vanilla `Direction.orderedByNearest`.

The old and the new code produced identical `t` dumps on Litematica 0.29.1 and on 0.29.0: 1,222,760 direction orders, 990,001 rail shapes, 396,288 clips, 31,800 panel positions, 4,752,000 culling decisions, 928 tool picks. Contract check `g` — 0 FAIL; dumps `b`, `c`, `e`, `i`, `j` match or differ only by noise (plants with a random age).

**Internal — GPL-3.0-or-later everywhere.** `LICENSE` is the full GPLv3 text (was GPLv2), `fabric.mod.json` says `GPL-3.0-or-later` (was `GPL-2.0-or-later`), `NOTICE.md` names the same licence instead of MIT and adds an unofficial add-on disclaimer (not affiliated with or endorsed by the Litematica and malilib authors; not an official Minecraft product) and credits for feature ideas.

## 0.0.25+26.3

**RU**

**fix — наблюдатели, поршни, раздатчики и бочки вставали по взгляду игрока, а не как в схеме.** Лицо этих блоков сервер считает по повороту головы игрока (`yHeadRot`), а пакет поворота разворачивает только тело: голова догоняет его лишь в следующем серверном тике (`Player.aiStep`). Подставленный угол уходил вместе с кликом, и сервер его ещё не видел — блок вставал туда, куда игрок смотрел на самом деле. Печей, ступеней, сундуков и всего, что встаёт по грани клика, это не касалось: их сторона берётся из тела. Теперь мод сверяет клик с тем, как его разберёт сервер с прежним поворотом головы, и если результат другой — сначала отдаёт угол ванильным пакетом движения и кликает, только когда угол пробыл на сервере не меньше 100 мс — два серверных тика, за которые сервер вписывает его в голову. Одного тика клиента мало: при FPS ниже 20 игра крутит несколько тиков в одном кадре, и угол с кликом попадали в одно окно между серверными тиками. Такая клетка встаёт на пару тиков позже, и только когда игрок смотрит не в ту сторону; следующие блоки с тем же углом — без задержки. Работает и без «Позы на тик вперёд»; с ней угол больше не уходит вплотную к клику.

- Проверка — новый режим харнесса `LL_COVERAGE=j` (`DumpLook`): наблюдатель и поршень на все 6 сторон, раздатчик, бочка и печи (контроль) через весь конвейер Easy Place (Litematica → `PlacementGuard` → пакеты), в выживании, протокол Easy Place выключен, как на обычном сервере; игрок подходит к каждой клетке с четырёх сторон, с «Позой на тик вперёд» и без. До правки без опции неверно встали 33 из 84 клеток (наблюдатели 12/24, поршни 12/24, раздатчики 6/12, бочки 3/8; печи 16/16 верно), с опцией — 1 из 84; после — 0 из 168. Серверный зонд харнесса подтверждает: в момент каждого такого клика голова на сервере уже смотрит по позе.

**EN**

**fix — observers, pistons, dispensers and barrels faced the way the player looked, not the way the schematic says.** The server takes these blocks' facing from the player's head yaw (`yHeadRot`), while a rotation packet only turns the body; the head catches up on the next server tick (`Player.aiStep`). The spoofed angle went out together with the click, before the server could see it, so the block faced wherever the player was really looking. Furnaces, stairs, chests and everything placed by the clicked face were unaffected: their side comes from the body. The mod now checks how the server would resolve the click with the old head yaw, and when the result differs it sends the angle with the vanilla movement packet first and clicks only after the angle has been on the server for at least 100 ms — two server ticks, in which the server applies it to the head. One client tick is not enough: below 20 FPS the game runs several ticks in one frame, and the angle and the click landed in the same gap between server ticks. Such a cell is placed a couple of ticks later, and only when the player looks the other way; the following blocks with the same angle go without delay. Works without "Pose one tick ahead" too; with it, the angle no longer goes out right before the click.

- Checked with the new `LL_COVERAGE=j` harness mode (`DumpLook`): observers and pistons in all 6 directions, dispensers, barrels and furnaces (control) through the whole Easy Place pipeline (Litematica → `PlacementGuard` → packets), in survival, with the Easy Place protocol off as on a regular server; the player approaches each cell from four sides, with "Pose one tick ahead" on and off. Before the fix, 33 of 84 cells came out wrong without the option (observers 12/24, pistons 12/24, dispensers 6/12, barrels 3/8; furnaces 16/16 right) and 1 of 84 with it; after — 0 of 168. The harness server probe confirms the head already faces the pose at each such click.

## 0.0.24+26.3

**RU**

**Новое — панель рядом с открытым контейнером.** Пока открыт сундук, бочка, шалкер или инвентарь, панель встаёт вплотную к окну контейнера: слева, верхом вровень с его верхом; слева тесно — справа; места нет ни с одной стороны — остаётся на своём обычном месте. Место считается в масштабированных пикселях экрана, поэтому масштаб GUI, разрешение окна и масштаб самой панели учитываются сами собой; открытая книга рецептов (инвентарь, верстак, печи) и столбец эффектов в инвентаре не перекрываются. Тумблер «Рядом с открытым контейнером» на вкладке HUD (`hudBesideContainer`, вкл.).

**Новое — экран настроек шире и на весь экран.** Панель настроек растёт под ряд вкладок — с установленным Zone Focus их восемь, и русские названия не помещались в одну строку — теперь до двух третей окна вместо половины. Если и этого мало, тумблер «Настройки на весь экран» в разделе «Экран настроек» вкладки HUD (`settingsFullscreen`, выкл.) растягивает экран настроек на всё окно.

**Клавиши — Esc снимает клавишу.** Во время ввода Esc заканчивает ввод тем, что нажато; ничего не нажато — клавиша остаётся «не задано», как в malilib. Прежнее значение возвращает повторный клик по полю, значение по умолчанию — кнопка сброса в строке; подсказка — во всплывающем тексте строки. Общая библиотека aell-ui: то же в Zone Focus 0.0.7, Ghost Alpha 0.0.4 и Easy Place Fix+ 1.0.12.

**fix — значок поиска в заголовке настроек.** Глиф «⌕» игрового шрифта сидел ниже строки подсказки; теперь лупа рисуется пиксельной маской 7×7 вровень с текстом «Поиск настройки» и золотым цветом.

**fix — подсказка «Поиск настройки» не пропадала при клике по полю.** В фокусе подсказки больше нет, вместо неё мигающий курсор: видно, что поле приняло клик.

- Проверка — тур `LL_COVERAGE=i` (`TourShots`): снимки вкладок, захват клавиши и Esc, полноэкранный режим, сундук и инвентарь с книгой рецептов при масштабах GUI 2, 3 и 4 с проверкой, что панель не ложится на окно.

**EN**

**New — panel beside an open container.** While a chest, barrel, shulker box or the inventory is open, the panel moves next to the container window: to its left, top edges aligned; when the left side is too tight — to its right; with no room on either side it stays where it usually is. The spot is computed in scaled screen pixels, so GUI scale, window resolution and the panel's own scale take care of themselves; an open recipe book (inventory, crafting table, furnaces) and the effects column in the inventory are never covered. Toggle "Beside an open container" on the HUD tab (`hudBesideContainer`, on).

**New — wider settings and a full-screen option.** The settings panel grows to fit its tab row — with Zone Focus installed there are eight tabs and the Russian names did not fit in one line — now up to two thirds of the window instead of a half. When even that is not enough, the "Full-screen settings" toggle in the "Settings screen" section of the HUD tab (`settingsFullscreen`, off) stretches the settings screen over the whole window.

**Hotkeys — Esc unbinds.** While capturing a key, Esc finishes the capture with whatever was pressed; nothing pressed — the key stays "not set", as in malilib. A second click on the field brings the previous value back, the reset button in the row — the default; the row tooltip says so. Shared aell-ui library: the same in Zone Focus 0.0.7, Ghost Alpha 0.0.4 and Easy Place Fix+ 1.0.12.

**fix — search icon in the settings header.** The font glyph "⌕" sat below the hint line; the magnifier is now a 7×7 pixel mask aligned with the "Search settings" text and painted gold.

**fix — the "Search settings" hint stayed after a click on the field.** In focus the hint is gone and a blinking caret shows instead: the field visibly took the click.

- Checked with the `LL_COVERAGE=i` tour (`TourShots`): tab screenshots, key capture and Esc, full-screen mode, a chest and the inventory with the recipe book at GUI scales 2, 3 and 4, asserting the panel never overlaps the window.

## 0.0.23+26.3

**RU**

**Новое — сущности схемы в списке и в Easy Place.** Стойки для брони, подушки (26.3), рамки и светящиеся рамки (вместе с предметом внутри), картины, кристаллы Энда, вагонетки и лодки.

- **Список.** Каждая сущность — строка того предмета, которым её ставят: подушка своего цвета, рамка и отдельно предмет в ней. Итог — по всей схеме, из её данных, как у блоков (у самой Litematica 0.29.1 — только по загруженным чанкам мира схемы, и итог плавал). «Не хватает» и «не то» — где чанки прогружены, сверка с голограммой: цвет подушки, предмет и его поворот в рамке, сюжет картины, поворот стойки (шаг 45°) и подушки. Предмет, который уже есть строкой (камень в рамке и в стене), — одна строка. Мобы не считаются. Тумблер «Считать сущности» на вкладке HUD (`countEntities`, вкл.).
- **Easy Place.** Наведите прицел на голограмму сущности — мод возьмёт предмет, кликнет по опоре и повернёт голову, как у голограммы: стойка на плите и подушка на ковре встают на свою высоту, рамка — на свою грань, потом в неё кладётся предмет и доворачивается кликами. Картина ставится, только если игра выберет сюжет того же размера (сюжет среди одинаковых она выбирает случайно — тогда в списке «не то»); лодки не ставятся — игра ставит лодку туда, куда смотрит игрок, и с его поворотом. Темп, поворот и ожидание сервера — общие с блоками. Тумблер «Ставить сущности» на вкладке Easy Place (`easyPlaceEntities`, вкл.; только у Material HUD, в общий с Easy Place Fix+ список не входит).
- Проверка — харнесс `LL_COVERAGE=f` (`DumpEntities`): образец из 14 сущностей → схема → установка в выживании → сверка с образцом и список до/после; плюс позиции у повёрнутого размещения против голограмм Litematica.

**fix — светящиеся ягоды и другие лозы в списке.** На лозу любой длины считался один предмет — её кончик: у «тел» лоз (`cave_vines_plant`, плакучей, вьющейся, ламинарии) своего предмета нет, и Litematica выбрасывала эти клетки из списка. Теперь каждая клетка — предмет головы (лечится в самой Litematica: и её список, и её Easy Place). Возраст лозы при сверке больше не «не то» (он случайный при установке), голова на месте тела — норма. Лиана считается по граням. Ламинария и другое в воде — «не хватает», а не «не то». У полки «запитана» и цепочка (от редстоуна) — не «не то».

**Новое — доводка растений Easy Place** (тумблер «Доводить инструментами»): кончик лозы «обрезан» (плакучая, вьющаяся, светящаяся, ламинария) — ножницами, с приседом, чтобы не сорвать ягоды; ягоды на пещерной лозе — костной мукой, лишние — снимаются кликом; трутовик возраста 1 — костной мукой. Мука плакучей и вьющейся лозам и ламинарии не даётся — она их удлиняет.

**Новое — запитанная полка** («второе состояние», цельная панель): клик по ней меняет всю цепочку из до трёх полок с панелью быстрого доступа. Мод сперва раскладывает панель (лишнее — в инвентарь, нужное — ровно по штуке), потом кликает; без всех предметов обмен не делает. **fix:** полка без питания забирала из руки всю стопку — теперь в руке ровно нужное число.

**fix — прицел Easy Place для лоз.** Помощь прицелу (тонкая голограмма лозы, клетки без опоры) не работала при «Easy Place First»: Litematica зовёт другую перегрузку трассировки, чем перехватывал мод. Для лоз — включена; колонна строится взглядом снизу.

**Документация модулей** — `docs/MODULES.md` (модули, точки контакта с Litematica/malilib/MC, что тихо ломается, чем проверять, журнал изменений) и автопроверка контрактов `LL_COVERAGE=g` по карте `docs/modules.tsv`. Проверка растений и полок — `LL_COVERAGE=h`.

**EN**

**New — schematic entities in the list and in Easy Place.** Armor stands, cushions (26.3), item frames and glow item frames (with the item inside), paintings, end crystals, minecarts and boats.

- **List.** Each entity is a row of the item that places it: a cushion of its color, a frame and, separately, the item in it. Totals cover the whole schematic, from its data, like blocks (Litematica 0.29.1 itself counts only loaded chunks of the schematic world, so its total drifted). "Missing" and "wrong" are counted where chunks are loaded, against the ghost: cushion color, the item and its rotation in a frame, the painting motive, the armor stand (45° steps) and cushion rotation. An item that already has a row (stone in a frame and in a wall) stays one row. Mobs are not counted. Toggle "Count entities" on the HUD tab (`countEntities`, on).
- **Easy Place.** Aim at an entity's ghost — the mod takes the item, clicks the supporting block and turns the head the way the ghost faces: a stand on a slab and a cushion on a carpet end up at their height, a frame on its face, then the item goes in and gets rotated by clicks. A painting is placed only if the game would pick a motive of the same size (among equal sizes it picks at random — the list then shows it as wrong); boats are not placed — the game puts a boat where the player looks, turned the way the player faces. Pace, rotation and waiting for the server are shared with blocks. Toggle "Place entities" on the Easy Place tab (`easyPlaceEntities`, on; Material HUD only, not part of the list shared with Easy Place Fix+).
- Checked with the `LL_COVERAGE=f` harness (`DumpEntities`): a 14-entity sample → schematic → placing in survival → comparison with the sample and the list before/after; plus positions of a rotated placement against Litematica's ghosts.

**fix — glow berries and other vines in the list.** A vine of any length counted as one item — its tip: vine "bodies" (`cave_vines_plant`, weeping, twisting, kelp) have no item of their own and Litematica dropped those cells. Now every cell counts as the head's item (fixed inside Litematica: its own list and Easy Place too). Vine age no longer counts as wrong (it is random on placement), a head where a body should be is fine. Vines (`vine`) count per face. Kelp and other things in water are "missing", not "wrong". A shelf's "powered" and chain (redstone-driven) are not "wrong".

**New — Easy Place finishes plants** ("Finish with tools"): a "cut" vine tip (weeping, twisting, glow berry vines, kelp) — with shears, sneaking so the berries stay; berries on glow berry vines — bone meal, extra ones are picked; a shelf mushroom of age 1 — bone meal. Weeping/twisting vines and kelp never get bone meal — it grows them.

**New — powered shelf** ("second state", the solid panel): clicking it swaps the whole chain of up to three shelves with the hotbar. The mod lays out the hotbar first (extra items to the inventory, the needed ones exactly), then clicks; without all items it does not swap. **fix:** an unpowered shelf took the whole stack from the hand — now the hand holds exactly the needed count.

**fix — Easy Place aim for vines.** The aim helper (thin vine ghost, cells without support) did not work with "Easy Place First": Litematica calls a different ray-trace overload than the mod hooked. Enabled for vines; a column is built by looking at it from below.

**Module docs** — `docs/MODULES.md` (modules, contact points with Litematica/malilib/MC, what breaks silently, how to verify, change log) and the contract check `LL_COVERAGE=g` with the `docs/modules.tsv` map. Plants and shelves check — `LL_COVERAGE=h`.

## 0.0.22+26.3 (стабильная)

Номер 0.0.22 переиспользован: прежняя 0.0.22 снята с Modrinth 22.09. Выложенная по ошибке 0.0.23 снята 30.09. В тот же день 0.0.22 заменена: из неё убран пересчёт «есть в инвентаре» для обычного баннера (`DataItems.honestAvailability`) — строка без узора снова засчитывает и узорные баннеры из инвентаря, как в 0.0.21. Код 0.0.22 = выложенная 0.0.21 + мост `MaterialListCompat`.

**RU**

**fix — краш на Litematica 0.29.1.** В 0.29.1 список материалов Litematica научился считать сущности: у `MaterialListUtils.getMaterialList` добавились три карты сущностей, а прежний вариант убрали. Сборка против 0.29.0 падала на первом же пересчёте (`NoSuchMethodError` в `LayerCountManager.publishResults`). Теперь метод ищется один раз при запуске (`core/MaterialListCompat`): есть новый — в него идут пустые карты сущностей (HUD считает блоки, как и раньше), нет — берётся старый. Один jar работает и с 0.29.0, и с 0.29.1.

**EN**

**fix — crash with Litematica 0.29.1.** Litematica 0.29.1 counts entities in the material list: `MaterialListUtils.getMaterialList` gained three entity maps and the old overload was removed, so a build against 0.29.0 crashed on the first recount (`NoSuchMethodError` in `LayerCountManager.publishResults`). The method is now looked up once at startup (`core/MaterialListCompat`): the new one gets empty entity maps (the HUD counts blocks, as before), otherwise the old one is used. One jar works with both 0.29.0 and 0.29.1.

**fix — a plain banner row no longer counts patterned banners from the inventory.** Litematica counts "available" by item, ignoring components, so patterned banners in the backpack were credited to the "Black Banner" row. The plain row now counts only plain banners (same for pots with sherds and heads with a profile); patterned rows still count exact matches only (`DataItems.honestAvailability`). It was described under 0.0.21 but did not make it into the published jar.

## 0.0.21+26.3

**RU**

**Стабильная база + сегодняшние фичи.** Номер 0.0.21 переиспользован: прежние 0.0.21 и 0.0.22 сняты с Modrinth. Сборка собрана от 0.0.20 (16.09): в неё вошли список, который держится при смене измерения, узоры баннеров и черепки горшков в списке, установка заполненного шалкера при «Только по схеме» и сообщение о недостающем баннере с узором. **Не вошла** достройка второй половины плиты (19.09): тот предикат менял, что прицел и страховка считают работой, — снят до отдельной проверки, полная плита на месте половины снова пропускается, как в 0.0.20. 

**fix — обычный баннер не засчитывает узорные из инвентаря.** Litematica считает «есть» по предмету без учёта компонентов: три баннера с узором в рюкзаке шли в зачёт строке «Black Banner». Теперь строка без узора считает только баннеры без узора (то же для горшков с черепками и голов с профилем), а узорные строки — по-прежнему только точное совпадение (`DataItems.honestAvailability`). Проверка — харнесс `LL_COVERAGE=e`, третий проход: +2 обычных баннера → у «Black Banner» «есть 2», у узорной строки остаётся 3.

**EN**

**Stable base + today's features.** The number 0.0.21 is reused, the earlier 0.0.21 and 0.0.22 were withdrawn from Modrinth. Built from 0.0.20 (16.09): the list that survives a dimension change, banner patterns and pot sherds in the list, placing a filled shulker with "Schematic only" on, and the message about a missing patterned banner are in. **Not in:** completing the second half of a slab (19.09) — that predicate changed what the aim and the guard count as work; it is pulled until it is verified separately, so a double slab over a half is skipped again as in 0.0.20. 

**fix — a plain banner row no longer counts patterned banners from the inventory.** Litematica counts "available" by item, ignoring components: three patterned banners in the backpack were credited to the "Black Banner" row. Now the plain row counts only plain banners (same for pots with sherds and heads with a profile), and the patterned rows still count exact matches only (`DataItems.honestAvailability`). Checked with the `LL_COVERAGE=e` harness, third pass: +2 plain banners → "Black Banner" shows 2, the patterned row stays 3.

## 0.0.22+26.3

**RU**

**new — в списке виден узор баннера и черепки горшка.** Узор и черепки лежат в сущности блока, а Litematica считает материалы по состоянию — поэтому три баннера с разными узорами слипались в одну строку «баннер», а человек нёс к стройке не то. Теперь такие клетки считаются своим ключом: предмет вместе с узором (черепками, профилем головы). У каждого узора своя строка — баннер рисуется с узором, горшок с черепками, в подписи названы первые два слоя (или черепки), остальные числом. «Есть в инвентаре» считает только точное совпадение — баннер без узора за нужный не сойдёт. Баннер того же цвета, но с другим узором на месте считается несовпадением, а не готовой клеткой. Узор берётся из сущности блока в мире схемы, а она есть только в прорисованных чанках: дальние клетки считаются по состоянию, как раньше, и расщепляются по узорам, когда подойдёшь.

**EN**

**new — the list shows the banner pattern and pot sherds.** Patterns and sherds live in the block entity while Litematica counts materials by block state, so three banners with different patterns collapsed into one "banner" row and you brought the wrong ones. Such cells are now counted by their own key: the item together with its pattern (sherds, head profile). Every pattern gets its own row — the banner is drawn with its pattern, the pot with its sherds, and the label names the first two layers (or the sherds) with the rest as a number. "In inventory" counts exact matches only — a plain banner does not pass for a patterned one. A banner of the same colour with a different pattern in place counts as a mismatch, not a finished cell. The pattern comes from the block entity in the schematic world, which exists only in rendered chunks: far cells are counted by state as before and split by pattern once you come closer.

## 0.0.21+26.3

**26.3.** Сборка под Minecraft 26.3 (Litematica 0.29.0, MaLiLib 0.30.1). Функционально то же, что и 0.0.21+26.2; перенесено на новые API: ввод через SDL вместо GLFW (коды клавиш — `InputConstants`), таблички (`SignTextSlot`), `swing` с анимацией, таблицы превращений лопаты/мотыги/топора читаются из data-driven реестра `block_transformer` (класс `ToolTables`) вместо снесённых `ShovelItem`/`HoeItem`/`AxeItem`.

**26.3.** Build for Minecraft 26.3 (Litematica 0.29.0, MaLiLib 0.30.1). Functionally the same as 0.0.21+26.2; ported to the new APIs: SDL input instead of GLFW (`InputConstants` key codes), signs (`SignTextSlot`), `swing` with an animation, and the shovel/hoe/axe conversion tables are read from the data-driven `block_transformer` registry (`ToolTables`) instead of the removed `ShovelItem`/`HoeItem`/`AxeItem`.

## 0.0.21+26.2

**RU**

**new — список держится при смене измерения.** Litematica хранит размещения по измерениям, поэтому раньше стоило уйти из Незера в Оверворлд — и HUD пропадал: считать нечего. Теперь последний честный счёт остаётся на экране снимком: итоги по блокам заморожены, а колонка «есть в инвентаре» и поиск по складам живут — можно ходить по оверворлдским сундукам и собирать материалы для незерской стройки. В подвале панели написано, из какого измерения снимок и какие размещения в него вошли. Тумблер «Держать список при смене измерения» во вкладке HUD, по умолчанию включён. Снимок снимается заново при каждом честном счёте, стирается при выходе из мира и когда в том же измерении размещение выключили намеренно.

**fix — заполненный шалкер ставится при включённом «Только по схеме».** Шалкер с предметами внутри — это склад игрока, который он принёс к стройке, а не блок схемы; раньше страховка отменяла такой клик внутри области без единого слова. Теперь ручная установка заполненного шалкера проходит всегда; автоклик Litematica с шалкером в руке под исключение не попадает — там схема просит именно шалкер. Заодно «Не копать лишнего» больше не мешает разобрать свой шалкер за границей области.

**fix — вторая половина плиты снова встаёт.** Клетка, где схема просит полную плиту, а стоит половина, считалась неисправимой: свойство `type` разошлось, клетка занята — значит «кликом не поправить, надо ломать». Прицел такую клетку бросал и уводил луч дальше, поэтому до кода, который добивает плиту вторым кликом, дело не доходило никогда. Теперь установка на месте (клик по стоящей половине тем же предметом даёт полную плиту) вынесена в общий предикат, и его же спрашивают обе половины мода — «есть ли здесь работа» и сам конвейер. Проверено на схеме с 25 клетками полных плит семи видов: до правки все 25 считались неисправимыми, после — все 25 стали работой.

**fix — мод больше не молчит, когда нужен баннер с конкретным узором.** Узор баннера и черепки горшка лежат не в состоянии блока, а в его сущности, поэтому требуемый предмет мод берёт с данными — иначе в схему встал бы баннер без узора. В выживании Litematica ищет такой предмет точным совпадением компонентов, и если в инвентаре его нет, установка тихо не происходит вовсе: клетка остаётся пустой без единого слова, а список материалов при этом показывает её обеспеченной — он просит просто «баннер», потому что кеш требуемых предметов у Litematica ключуется по состоянию блока. Теперь про такую клетку мод говорит один раз: какой именно предмет нужен и почему список врёт.

**EN**

**new — the list survives a dimension change.** Litematica stores placements per dimension, so leaving the Nether for the Overworld used to empty the HUD: nothing to count. Now the last real count stays on screen as a snapshot: block totals are frozen, while the "in inventory" column and storage search keep working — walk your Overworld chests and gather materials for a Nether build. The footer names the dimension the snapshot came from and the placements in it. Toggle "Keep the list across dimensions" on the HUD tab, on by default. The snapshot is retaken on every real count, dropped on leaving the world and when a placement is deliberately disabled in the same dimension.

**fix — a filled shulker box can be placed with "Schematic only" on.** A shulker with items inside is the player's storage brought to the site, not a build block; the guard used to cancel such a click inside the area without a word. Manual placement of a filled shulker now always goes through; Litematica's own automated click with a shulker in hand is not exempt — there the schematic asks for a shulker. "Do not dig the wrong thing" also no longer stops you from picking your shulker back up outside the area.

**fix — the second half of a slab is placed again.** A cell where the schematic asks for a double slab while a half stands there counted as unfixable: `type` differs and the cell is occupied, so "no click can fix this, it has to be broken". The aim dropped such a cell and moved the ray on, so the code that completes the slab with a second click was never reached. Placement that upgrades a block in place (clicking a standing half with the same item gives a double slab) is now a shared predicate, asked by both halves of the mod — "is there work here" and the pipeline itself. Checked on a schematic with 25 double-slab cells of seven kinds: before the fix all 25 counted as unfixable, after it all 25 count as work.

**fix — the mod no longer stays silent when a banner with a specific pattern is needed.** A banner's pattern and a pot's sherds live in the block entity, not in the block state, so the mod takes the required item with its data — otherwise a plain banner would go into the build. In survival Litematica looks that item up by an exact component match, and when it is not in the inventory nothing is placed at all: the cell stays empty without a word, while the material list shows it as covered — it asks for a plain banner, because Litematica's required-item cache is keyed by block state. The mod now says it once per cell: which item is actually needed and why the list disagrees.

## 0.0.20+26.2

**RU**

**new — «Неровный темп».** К паузе между автоматическими кликами случайно добавляется ещё один тик, чтобы интервал не был идеально ровным: рука не кликает как метроном, а часть timer-античитов ловит именно ровный ритм. Немного замедляет стройку, поэтому выключено по умолчанию; включать, если сервер жалуется и на меньшем «Блоков в секунду».

**new — «Не копать лишнего».** Пока в Litematica включён режим Easy Place, левый клик не ломает блоки вне области схемы и блоки, уже стоящие ровно как в схеме: копать можно только лишнее или неверное внутри области. Причина отказа показывается строкой над хотбаром, не чаще раза в секунду. Мгновенная ломка в креативе перехватывается тоже. Выключено по умолчанию.

**new — «Брать инструмент для копания».** Перед тем как блок начнёт ломаться, в руку берётся самый быстрый подходящий инструмент из инвентаря — так же, как Easy Place уже берёт нужный блок для установки: хотбар сменой слота, остальной инвентарь через штатный pick-block Litematica. Инструмент, с которым блок не выпадет, не выигрывает у пустой руки. Только в выживании и только при включённом Easy Place. Выключено по умолчанию.

**new — «Заменять почву».** Земля, дёрн, каменистая земля, тропинка, корневая земля, подзол и мицелий: если в клетке стоит один из них, а схема просит другой, обычный Easy Place упирается в «клетка занята». Теперь мод выкапывает лишний блок теми же вызовами, что и удерж левой кнопки, а следующий проход Easy Place ставит нужный. Где блок доводится на месте мотыгой или лопатой, копать не надо — это по-прежнему делает «Доводить блоки инструментом», и оно идёт первым. Раскопки идут по одной клетке и бросаются, если сервер не отдал блок за четыре секунды. Выключено по умолчанию, потому что ломает блоки.

Четыре новых тумблера — ответ на возможности форка EasyPlaceFix 0.6.5–0.6.6 (placementJitter, Excavation guard, Auto tool switch, Terrain auto-replace). Реализация своя, код форка не заимствован; источник идей указан в NOTICE. (Уточнение 06.10.2026: проверка прав показала, что выбор инструмента `ToolPick` повторял форму кода форка слишком близко; в 0.0.26 он переписан своими словами с тем же поведением.) Настройка нот и открытие контейнеров, тоже заявленные форком, у нас были и раньше.

**EN**

**new — "Uneven pace".** A random extra tick is added to the pause between automated clicks, so the interval is not perfectly regular: a hand never clicks like a metronome, and some timer anticheats flag exactly that even rhythm. Costs a little speed, so it is off by default; turn it on if the server still complains at a lower "Blocks per second".

**new — "Do not dig the wrong thing".** While Litematica's Easy Place mode is on, a left click does not break blocks outside the schematic area or blocks already standing exactly as the schematic asks: only leftover or wrong material inside the area can be dug. The reason is shown above the hotbar, at most once a second. Creative instant breaking is intercepted too. Off by default.

**new — "Pick the tool for digging".** Before a block starts breaking, the fastest suitable tool from the inventory is taken in hand — the way Easy Place already takes the right block for placing: the hotbar by switching the slot, the rest of the inventory through Litematica's own pick-block. A tool that would not drop the block never beats a bare hand. Survival only, and only while Easy Place mode is on. Off by default.

**new — "Replace soil".** Dirt, grass block, coarse dirt, dirt path, rooted dirt, podzol and mycelium: when the cell holds one of them and the schematic asks for another, plain Easy Place stops at "cell occupied". The mod now digs the wrong block out with the same calls a held left click makes, and the next Easy Place pass puts the right one down. Where a hoe or shovel can convert the block in place, nothing is dug — "Finish blocks with tools" still does that, and it runs first. One cell is dug at a time, and the dig is dropped if the server has not released the block within four seconds. Off by default because it breaks blocks.

The four new toggles answer the features of the EasyPlaceFix fork 0.6.5–0.6.6 (placementJitter, Excavation guard, Auto tool switch, Terrain auto-replace). The implementation is our own and no code was taken from the fork; the source of the ideas is credited in NOTICE. (Clarification 06.10.2026: a rights review found that the tool choice in `ToolPick` followed the fork's code too closely; it was rewritten in our own form with the same behaviour in 0.0.26.) Note block tuning and container opening, also advertised by the fork, were already here.

## 0.0.19+26.2

**RU**

**fix — Easy Place больше не роняет лишний блок на клетку выше.** Решатель сверяется со схемой только по тем свойствам, которые задаёт сам клик, и прощает те, что достаются от окружения: форму ступеней, `snowy` у травы, `distance` и `persistent` у листвы, соединения забора. Проверка «есть ли здесь работа» этого списка не знала и считала работой любое расхождение, кроме четырёх частных случаев. Из-за расхождения двух взглядов уже поставленная ступень, у которой сосед по маршу ещё не встал, выглядела недоделанной: мод снимал с клетки двухсекундную блокировку Litematica, клик уходил в занятую клетку, а ванильная установка уводит такой клик на клетку за гранью — чаще всего вверх. По реестру 26.2 под это попадало 110 видов блоков: 64 ступени, 12 видов листвы, три почвы и мелочь. Теперь клетка, у которой вся разница со схемой лежит вне возможностей установки, считается готовой, а вторая страховка отменяет любой автоматический клик в клетку, где уже стоит то, что просит схема. Свечи, морские огурцы и слои снега работой остаются: их как раз добавляют новые клики.

**fix — темп установки теперь считается и для починки состояний.** Доводка двери, люка, свечи, повторителя и полки стоит в самом начале обработчика Easy Place и шла мимо «Блоков в секунду» целиком: в тике, где уже прошла установка, она добавляла второе взаимодействие и ещё пару пакетов поворота. Серверный timer-античит смотрит ровно на это. Бюджет у починки и у установки теперь общий, то есть за интервал уходит одно действие, а не два.

**new — «Поза на тик вперёд»: мод больше не шлёт своих пакетов поворота.** Подмена взгляда стоила двух лишних пакетов на каждый блок: один перед кликом, второй на восстановление угла. Ванильный клиент отправляет ровно один пакет движения за тик, а стоя на месте — один раз в двадцать тиков, и серверная timer-проверка считает именно их; в тике с установкой уходило три вместо одного. Теперь нужный угол применяется ровно на время сборки ванильного пакета движения, то есть его уносит собственный пакет клиента, а клик уходит следующим тиком уже без единого своего пакета. Камера при этом не дёргается: между подстановкой и снятием кадры не рисуются. Плата: клетка, для которой нужен поворот, встаёт на тик позже, а пока угол держится, сервер не видит настоящего взгляда, поэтому удар мышью в это время он посчитает по подставленному углу — удержание снимается, как только игрок перестал строить или нажал атаку. Если пакет движения в тике не уходит вовсе, например игрок верхом, схема на две секунды отключается сама и работает по-старому, чтобы установка не встала. Опция выключена по умолчанию: включайте, если сервер кикает с «timer».

**EN**

**fix — Easy Place no longer drops a spare block one cell up.** The solver compares against the schematic only on properties a click can set, and forgives the ones the surroundings decide: stair shape, `snowy` on grass, `distance` and `persistent` on leaves, fence connections. The "is there work here" test knew nothing of that list and read any difference as work, except for four special cases. The two views disagreed, so an already-placed stair whose neighbour along the flight was still missing looked unfinished: the mod lifted Litematica's two-second hold on the cell, the click landed on an occupied cell, and vanilla placement pushes such a click one cell past the clicked face, usually upwards. By the 26.2 registry that covered 110 block types: 64 stairs, 12 kinds of leaves, three soils and odds and ends. A cell whose only difference from the schematic lies beyond what placement can set now counts as done, and a second guard cancels any automated click into a cell that already holds what the schematic asks for. Candles, sea pickles and snow layers still count as work: those are what extra clicks add.

**fix — the placement rate now covers state repairs too.** Finishing a door, trapdoor, candle, repeater or shelf runs at the very start of the Easy Place handler and bypassed "blocks per second" entirely: in a tick where a placement had already gone out, it added a second interaction and another pair of rotation packets. That is exactly what a server-side timer anticheat watches. Repairs and placements now share one budget, so one action goes out per interval instead of two.

**new — "Aim one tick ahead": the mod no longer sends rotation packets of its own.** Look substitution cost two extra packets per block: one before the click, one to restore the angle after it. A vanilla client sends exactly one movement packet per tick, and only one every twenty ticks while standing still, and a server-side timer check counts exactly those; a tick with a placement carried three instead of one. The needed angle is now applied only for as long as vanilla builds its own movement packet, so that packet carries it, and the click goes out on the next tick without a single packet of ours. The camera does not jerk: no frame is drawn between substituting and restoring. The price: a cell that needs turning is placed one tick later, and while the angle is held the server does not see where you really look, so a left-click in that window is resolved from the substituted angle - the hold is released as soon as you stop building or press attack. If no movement packet goes out in a tick at all, riding a mount for instance, the scheme switches itself off for two seconds and works the old way so placement never stalls. Off by default: turn it on if a server kicks you with "timer".

## 0.0.18+26.2

**RU**

**Встроенный модуль Zone Focus вырезан — у него есть отдельная (standalone) версия.** Движок зон целиком живёт в моде **Litematica Zone Focus** (https://modrinth.com/mod/litematica-zone-focus) — поставьте его, если пользовались зонами. Совместная работа двух модов не изменилась ни в чём: подсчёт по зоне, имя зоны в заголовке панели, проверка и поиск по зоне, общий ряд кнопок на экранах Litematica — как раньше; вкладка «Zone Focus» в настройках Material HUD остаётся при установленном отдельном моде и, как и раньше, правит его живые настройки. Зоны не теряются: файл зон и так принадлежал отдельному моду.

**Пять переключателей убраны — их поведение теперь включено всегда.** «Открывать двери, включать рычаги», «Заполнять полки» и «Перебирать кликами» в Easy Place, «Листать в контейнерах» и «Считать при скрытой панели» в HUD. Это были страховки на время обкатки; режимы устоялись, выключать их незачем — строки настроек убраны, а «Перебирать кликами» заодно перестал быть выключенным по умолчанию. Старые значения в конфиге просто игнорируются.

**fix — Easy Place больше не ставит лишний забор поверх только что поставленного.** У забора, ограды, стены, панели, решётки и провода соединения с соседями задаются не кликом, а окружением: пока сосед по линии не построен, стоящий блок отличается от схемы одним-двумя соединениями. Мод считал такую клетку недоделанной и снимал с неё двухсекундную блокировку Litematica — клик уходил в уже занятую клетку, а ванильная установка уводит такой клик на клетку за гранью, чаще всего вверх. Собственная защита Litematica здесь не срабатывает, а наша («Только по схеме») ловила лишь клетки внутри области размещения — над верхним рядом забора она уже кончается. Теперь клетка с расхождением только по соединениям считается готовой, а любой съехавший клик Easy Place в пустую по схеме клетку отменяется, где бы она ни лежала.


**EN**

**The built-in Zone Focus module has been cut out — it exists as a standalone mod.** The zone engine lives entirely in **Litematica Zone Focus** (https://modrinth.com/mod/litematica-zone-focus) — install it if you used zones. Nothing changes in how the two mods work together: zone-scoped counting, the zone name in the panel header, verification and storage search across the zone, the shared button row on Litematica's screens — all as before; the "Zone Focus" tab in Material HUD's settings still appears when the standalone mod is installed and, as before, edits its live settings. Zones are not lost: the zones file always belonged to the standalone mod.

**Five toggles are gone — their behaviour is now always on.** "Open doors, set levers", "Fill shelves" and "Cycle by clicking" in Easy Place, "Page inside containers" and "Count with the panel hidden" in the HUD. They were safety switches while the features were new; the features have settled and turning them off served no purpose — the settings rows are removed, and "Cycle by clicking" is no longer off by default. Old config values are simply ignored.

**fix — Easy Place no longer stacks a spare fence on top of the one it just placed.** Fences, fence gates, walls, panes, bars and redstone wire get their connections from the surroundings, not from the click: until the neighbour along the line is built, the placed block differs from the schematic by a connection or two. The mod read such a cell as unfinished and lifted Litematica's two-second hold on it — the click then landed on an already occupied cell, and vanilla placement pushes such a click one cell past the clicked face, usually upwards. Litematica's own guard does not catch this, and ours ("Only from the schematic") only covered cells inside the placement box — which ends right above the top row of a fence. A cell that differs only in connections now counts as done, and any displaced Easy Place click into a cell the schematic leaves empty is cancelled, wherever that cell lies.

## 0.0.17+26.2

**RU**

**fix — Обрезка по зоне снова работает, когда установлен отдельный мод Litematica Zone Focus.** Material HUD несёт внутри себя копию Zone Focus, и она правильно засыпает при установленном отдельном моде — но всё равно забирала себе хуки отрисовки, а спящая ничего не фильтровала. Блоки схемы, блок-энтити, сущности, трассировка луча по схеме и маркеры несовпадений верификатора перестали ограничиваться активной зоной. Теперь спящий модуль оставляет эти хуки отдельному моду.

**fix — Easy Place: пропуск клетки больше не молчит.** При включённом «Пропускать то, что встанет неверно» мод отменяет клик, если не может поставить блок в том повороте, который просит схема, — чаще всего это ориентируемый блок вроде бревна. Litematica всё равно считает клетку обработанной и держит её две секунды, поэтому снаружи это выглядело так, будто мод просто перестал ставить вот здесь. Теперь он говорит об этом в чат, один раз на клетку, а причина уходит в «Отладку Easy Place».


**EN**

**fix — Zone clipping works again when the standalone Litematica Zone Focus mod is installed.** Material HUD carries a built-in copy of Zone Focus which correctly goes dormant when the standalone mod is present — but it still claimed the render hooks, and while dormant it filtered nothing. Schematic blocks, block entities, entities, the schematic raytrace and verifier mismatch markers stopped being limited to the active zone. The dormant module now leaves those hooks to the standalone mod.

**fix — Easy Place: a skipped cell is no longer silent.** With "Skip what would go in wrong" enabled, the mod cancels the click when it cannot place the block in the orientation the schematic asks for — most often an orientable block such as a log. Litematica counts the cell as handled either way and holds it for two seconds, so from the outside it looked as if the mod had simply stopped placing there. It now says so in chat, once per cell, and the reason goes to "Easy Place debug".

## 0.0.16+26.2

**RU**

**fix — кнопка «разметить зону» в инвентаре больше не прячется под панелью эффектов и не
наезжает на книгу рецептов.** Место считалось один раз при открытии экрана и всегда справа от
окна. Но справа же ванильный инвентарь рисует панель активных эффектов — с 26.x она именно
справа, а не слева, как было раньше, — и кнопка оказывалась под ней; раскрытая книга рецептов
сдвигает окно вправо, и кнопка уезжала к краю экрана; появившийся по ходу дела эффект, закрытая
книга или смена масштаба интерфейса ничего не пересчитывали. Теперь место выбирается каждый кадр
по свободному пространству: обычно справа вровень с верхом окна, при активных эффектах — слева,
а если слева раскрыта книга рецептов — справа под панелью эффектов. Панель считается по правилам
самой игры (32 px в узком варианте, иначе по ширине свободного места, шаг строки 33). На тесном
экране кнопка уходит под окно или прижимается к краю, оставаясь нажимаемой; в креативе вариант
«над окном» не рассматривается — там ряд вкладок. Чужие виджеты, добавленные на экран другими
модами, тоже учитываются: на них кнопка не встаёт.


**EN**

**fix — the "mark the zone" button in the inventory no longer hides under the effects panel or
collides with the recipe book.** Its position was decided once, when the screen opened, and always
sat to the right of the window. But the vanilla inventory draws the active effects panel there —
since 26.x it is on the right, not on the left as it used to be — so the button ended up beneath
it; an open recipe book shifts the window right and pushed the button to the screen edge; and an
effect appearing mid-session, the book being closed or the GUI scale changing recalculated
nothing. The spot is now picked every frame from the free space: normally to the right, level with
the top of the window; with active effects, to the left; and when the recipe book occupies the
left, to the right below the effects panel. The panel is measured by the game's own rules (32 px
in the narrow variant, otherwise the width of the free space, 33 px per row). On a cramped screen
the button moves below the window or hugs the edge, so it stays clickable; in creative the "above
the window" option is skipped because the tab row lives there. Widgets other mods add to the
screen are taken into account too — the button will not sit on top of them.

## 0.0.15+26.2

**RU**

**new — вкладка «Zone Focus» стала единственным домом настроек зон.** Пока движок ведёт
отдельный мод Litematica Zone Focus, вкладка раньше показывала только общие тумблеры: тумблер
модуля, действия с зонами и разметку она прятала, потому что жать в этом случае надо объекты
соседа, а не свои спящие. Теперь всё на месте. Тумблер модуля правит настройку соседа — она
одна на два мода, а не заводится вторая. Действия «Разбить схему на зоны», «Каждый подрегион =
зона», «Взять зону из выделения», «Список зон», «Сбросить зону» и «Разметить зону мышью» уходят
его движку через общий мост; неактивны они по тем же причинам, что и раньше (нет схемы, нет
выделения, нет зон). Добавилась строка «Настройки Zone Focus…» — вход в собственный экран
соседа: проверка зоны и его клавиши остались там, у Material HUD для этого своя «Проверка» и
свои клавиши. Кнопка «Зоны» из шапки настроек убрана — она вела на экран, которого больше нет.
Соседям добавлена точка API `createSettings(parent, backLabel, tabKey)`: экран настроек,
открытый сразу на нужной вкладке.

**fix — кнопка мода на экране слоёв Litematica не наезжает на новую вкладку «All».** В
Litematica 0.28.6 ряд вкладок на экране «Слои отрисовки» стал собираться циклом по всем вкладкам
настроек, и в нём появилась седьмая — «All»; прежде шесть кнопок создавались поимённо. Наш
распределитель слотов считал длину ряда по шести вкладкам, поэтому кнопка аддона вставала ровно
на ширину «All» левее и накрывала её. Теперь наличие вкладки спрашивается у самого экрана, а не
задаётся константой: с 0.28.6 ряд считается по семи вкладкам, на прежних версиях — по шести, и
раскладка верна в обоих случаях. Экран настроек Litematica спрашивал экран и раньше — расходился
только экран слоёв. Правка общая для всех наших аддонов Litematica, так что кнопки не наезжают ни
на вкладки Litematica, ни друг на друга.

**fix — темп Easy Place считается в тиках, а не по часам; сервер больше не видит пачек
кликов.** «Блоков в секунду» раньше мерилось миллисекундами между кликами. Серверный
timer-античит меряет пакеты на свой тик, и по часам два клика легко ложились в один тик:
несколько кадров на тик, догоняющие тики после лага — клиент насчитывал честную паузу, а
сервер получал пачку и кикал с «timer». Теперь пауза считается в тиках клиента (20 — каждый
тик, 10 — через тик, 5 — каждый четвёртый), часы остались лишь страховкой от второго клика
внутри того же тика, и больше одного автоматического клика за тик не уходит ни при каком
значении. Дожимающие клики многошаговых блоков — задержка повторителя, режим компаратора,
нота нотного блока (до 24 шагов) — идут через ту же очередь по одному за проход: после
клика клетка уходит в конец, следующий шаг получает, когда сервер подтвердит предыдущий и
очередь обойдёт остальных, ряд таких блоков дожимается по кругу. Раз включено «Перебирать
кликами», повторитель, компаратор и нотный блок ставятся в очередь дожима сразу при
установке — раньше каждый ждал, пока прицел снова окажется на нём. При смене мира, портале
и дисконнекте очереди дожима и отложенных установок и отсчёт темпа сбрасываются целиком:
координаты прошлого мира и пауза с прошлого сервера сюда не переносятся. Совместимость с
EasyPlaceFix 0.6.5: блокировка взгляда соседа читается через её срок годности
(`isLookLocked`), а не через сырой флаг — зависшая блокировка больше не держит наши
отложенные установки до истечения их лимита. Ни одной новой настройки.

**new — экран «Разбить схему на зоны»: ввод с клавиатуры и диаграмма разреза.** Число
зон или размер зоны теперь можно вписать руками: клик по числу открывает набор (цифры,
Backspace, Enter — принять, Esc — отменить, Tab — следующая ось, стрелки ↑/↓ — плюс-минус
один), значение зажимается в допустимый диапазон. Под предпросмотром — двумерная диаграмма
вида сверху, север сверху: форма схемы собирается из её подрегионов (синим — как слой и
прогресс в остальном интерфейсе), линии разреза жёлтым — цветом предпросмотра зоны в мире,
первая зона подсвечена цветом активной зоны из мира и помечена «1». Разрезы по Y сверху не
видны — в углу диаграммы напоминание «Y ×n». Диаграмма перерисовывается на каждое изменение
значений и честно повторяет неравные доли деления.

**new — встроенный модуль Zone Focus.** Третья вкладка модулей — рядом с Ghost Alpha и
Easy Place, со своей микроиконкой: деление большой схемы на зоны, подсчёт и поиск по
хранилищам в пределах зоны, разметка зоны мышью и обрезка рендера схемы по активной зоне.
Все действия с зонами (деление, зона на подрегион, зона из выделения, список, очистка)
живут прямо на вкладке; отдельного «экрана зон» у модуля нет, недоступные действия гаснут
с причиной. Своей проверки блоков у модуля тоже нет: обычная «Проверка» Material HUD сама
режет сканирование по активной зоне через область подсчёта — настраивается один раз для
слоёв и зон сразу. По умолчанию модуль выключен, все четыре клавиши не привязаны —
включается тумблером на вкладке; у каждой секции вкладки есть короткое описание, а
технический бюджет подсчёта убран в самый низ. При установленном отдельном моде Litematica Zone Focus
движком владеет он: встроенный модуль спит целиком, а вкладка остаётся и правит живые
настройки соседа через мост общих настроек (`ZoneFocusBridge`, тот же контракт, что у
Easy Place) — значения одни в обоих экранах, каждый мод пишет свой файл. Настройки зон
хранятся отдельной секцией `"ZoneFocus"` в `layerlist.json` (восемь имён `verify*`
совпадают с опциями собственной проверки блоков и в общей секции затёрли бы друг друга);
при первом запуске молча переезжают из `zonefocus.json`, и наоборот — отдельный мод при
первом запуске забирает секцию из `layerlist.json`. Файл зон `zonefocus_zones.json` общий
по построению: движок в каждый момент ровно один. Конфликт двух `@Redirect` по
`LayerRange.isPositionWithinRange` решён приоритетом: встроенные миксины обрезки рендера
объявлены с `priority = 1100, require = 0` и при живом соседе молча уступают ему вызов.

**fix — вторая половина двойного сундука ставится присед-кликом по боку первого.** Пару
из двух независимых кликов не собрать: первый сундук всегда встаёт одиночным, слипание
решает установка второго. Раньше этот клик доверялся перебору решателя, и пара собиралась
через auto-merge, где лицо считается из угла взгляда, — на сервере результат зависел от
того, дошёл ли поворот раньше клика, и вторая половина могла остаться одиночной. Теперь,
как только первая половина стоит одиночной нужным лицом, клик второй половины задаётся
сразу: присед и боковая грань первого сундука, смотрящая в клетку цели. Так пару ставит
ванильный игрок — ветка `secondaryUse` в `ChestBlock.getStateForPlacement` берёт и лицо,
и `type` от партнёра за кликнутой гранью, угол взгляда не участвует вовсе, и с посторонним
сундуком с другой стороны такой клик не слипнется. Работает для всех парных сундуков,
включая все виды медных. Результат по-прежнему сверяется симуляцией до клика.

**new — рычаги переключаются в положение из схемы.** Установка всегда даёт рычаг
выключенным: `powered` не задаётся никаким кликом установки. Рычаг, включённый в схеме,
теперь дожимается отдельным кликом — сразу после установки (та же очередь дожима, что у
дверей) или при наведении на уже стоящий не в том положении. Ровно рычаг, не всё с
`powered`: кнопка отожмётся сама, а у двери и наблюдателя `powered` — внешний редстоун,
кликом его не задать. Живёт под тумблером «Открывать двери и люки», который стал
«Открывать двери, включать рычаги».


**EN**

**new — the "Zone Focus" tab is now the single home for zone settings.** While the separate
Litematica Zone Focus mod drives the engine, the tab used to show only the shared toggles: the
module switch, the zone actions and the marking row were hidden, because in that case the buttons
have to press the neighbour's objects, not our sleeping ones. They are all there now. The module
switch edits the neighbour's own setting — one setting for both mods instead of a second one.
"Split the schematic into zones", "One zone per sub-region", "Take the zone from Litematica's
selection", "Zone list", "Clear the zone" and "Mark the zone with the mouse" are routed to its
engine over the shared bridge; they grey out for the same reasons as before (no schematic, no
selection, no zones). A "Zone Focus settings..." row was added — the way into the neighbour's own
screen: zone verification and its hotkeys stayed there, Material HUD has its own "Verification"
and its own keys. The "Zones" button is gone from the settings header — it led to a screen that no
longer exists. Addons get a new API entry point, `createSettings(parent, backLabel, tabKey)`: the
settings screen opened straight on the tab you name.

**fix — the mod's button on Litematica's render layers screen no longer overlaps the new "All"
tab.** In Litematica 0.28.6 the tab row on the render layers screen is built by looping over every
config tab, and a seventh one — "All" — joined it; before that six buttons were created by name.
Our slot allocator measured the row as six tabs, so the addon button landed exactly one "All"
width too far left and covered it. The screen is now asked whether it has that tab instead of the
answer being hardcoded: on 0.28.6 the row counts seven tabs, on earlier versions six, and the
layout is right either way. Litematica's config screen was already asked this way — only the
render layers screen disagreed. The fix is shared by all our Litematica addons, so the buttons
overlap neither Litematica's tabs nor each other.

**fix — Easy Place pacing is counted in ticks, not wall-clock time; the server no longer
sees click bursts.** "Blocks per second" used to measure milliseconds between clicks. A
server's timer anti-cheat counts packets per server tick, and by the clock two clicks easily
landed in one tick — several frames per tick, catch-up ticks after a lag spike — so the client
counted an honest pause while the server received a burst and kicked for "timer". The pause
is now counted in client ticks (20 — every tick, 10 — every other, 5 — every fourth), the
clock remains only as a backstop against a second click inside the same tick, and at most one
automated click per tick goes out at any setting. Follow-up clicks for multi-step blocks —
repeater delay, comparator mode, note block pitch (up to 24 steps) — run through the same
queue, one per pass: after a click the cell goes to the back and gets its next step once the
server has acknowledged the previous one and the queue has visited the others, so a row of such
blocks is finished round-robin. With "Cycle by clicking" on, repeaters, comparators and note
blocks are queued for follow-up right at placement — previously each waited for the crosshair
to return to it. On a world change, portal or disconnect the follow-up and deferred-placement
queues and the pacing counter are reset completely: coordinates from the previous world and a
pause from the previous server do not carry over. EasyPlaceFix 0.6.5 compatibility: the
neighbour's look lock is read through its expiry (`isLookLocked`) rather than the raw flag, so
a stuck lock no longer holds our deferred placements until their own limit runs out. No new
settings.

**new — "Split the schematic into zones" screen: keyboard input and a cut diagram.** The
zone count or zone size can now be typed in: clicking a number starts input (digits,
Backspace, Enter to accept, Esc to cancel, Tab for the next axis, ↑/↓ arrows for plus and
minus one), and the value is clamped to the allowed range. Below the preview sits a 2D
top-down diagram, north up: the schematic's shape is assembled from its sub-regions (in
blue — same as layers and progress elsewhere in the UI), the cut lines are yellow — the
world's zone-preview colour, and the first zone is highlighted with the world's active-zone
colour and marked "1". Y cuts are not visible from above — a "Y ×n" reminder sits in the
diagram corner. The diagram redraws on every value change and honestly shows uneven splits.

**new — built-in Zone Focus module.** A third module tab — next to Ghost Alpha and
Easy Place, with its own micro-icon: splitting a large schematic into zones, counting and
storage search within the zone, marking a zone with the mouse and clipping the schematic
render to the active zone. Every zone action (split, zone per sub-region, zone from the
selection, zone list, clear) lives right on the tab; the module has no separate "zones
screen", and unavailable actions grey out with a reason. It has no block check of its own
either: Material HUD's usual verification clips its scan to the active zone through the
count scope — configured once for layers and zones alike. The module is off by default and
all four keybinds ship unbound — it turns on with the toggle on its tab; every section of
the tab carries a short description, and the technical scan budget sits at the very bottom. With the separate
Litematica Zone Focus mod installed the engine is run by it: the built-in module sleeps
entirely, while the tab stays and edits the neighbour's live settings through the shared
options bridge (`ZoneFocusBridge`, the same contract as Easy Place) — the values are one
and the same on both screens, and each mod writes its own file. Zone settings live in
their own `"ZoneFocus"` section of `layerlist.json` (eight `verify*` names coincide with
the HUD's own block-check options and would clobber each other in the flat section); on
first run they migrate silently from `zonefocus.json`, and the separate mod adopts the
`layerlist.json` section on its first run in the same way. The zones file
`zonefocus_zones.json` is shared by construction: exactly one engine runs at any moment.
The clash of two `@Redirect`s over `LayerRange.isPositionWithinRange` is resolved by
priority: the built-in render-clip mixins are declared `priority = 1100, require = 0` and
silently yield the call while the neighbour is alive.

**fix — the second half of a double chest is placed with a sneak click on the side of the
first.** A pair can never be built out of two independent clicks: the first chest always
lands single, and it is the second placement that welds them. That click used to be left to
the solver sweep, so the pair assembled through auto-merge, where the facing is computed
from the look angle — on the server the outcome depended on whether the rotation packet
arrived before the click, and the second half could stay single. Now, as soon as the first
half stands single with the right facing, the second half's click is fixed up front: sneak
plus the side face of the first chest looking into the target cell. That is how a vanilla
player builds a pair — the `secondaryUse` branch of `ChestBlock.getStateForPlacement` takes
both the facing and the `type` from the partner behind the clicked face, the look angle
plays no part, and the click cannot weld to a stray chest on the other side. Works for
every pairable chest, all copper variants included. The result is still verified by
simulation before the click.

**new — levers are flipped to match the schematic.** Placement always leaves a lever off:
`powered` cannot be set by any placement click. A lever the schematic wants on now gets its
own click — right after placement (the same follow-up queue doors use) or when aiming at
one already standing in the wrong position. Strictly levers, not everything with `powered`:
a button would pop back out, and on doors and observers `powered` is external redstone no
click can set. Lives under the "Open doors and trapdoors" toggle, now renamed "Open doors,
set levers".

## 0.0.10+26.2 (не выпущено, часть 2)

Всё в этом разделе сделано по матрице покрытия Easy Place
(`docs/easyplace/coverage/COVERAGE.md`): прогон по всем 1196 ванильным блокам 26.2, 5791
проверка решателем и 3196 фактических установок. Главный результат прогона — ноль состояний,
которые ставятся кликом, но упущены решателем: всё найденное лежало по краям, и вот эти края.

**RU**

**fix — двойные сундуки не строились вообще.** `ChestBlock.getStateForPlacement` берёт
`type` не из клика, а из соседей мира: `candidatePartnerFacing` требует, чтобы рядом уже
стоял сундук того же блока с `type=single` и тем же лицом. У первой половины пары такого
соседа нет, поэтому любой из 13440 зондов давал `single`, а `CHEST_TYPE` числится
задаваемым установкой — `Plan.misplaced()` возвращал true, и «Пропускать то, что встанет
неверно» гасило клетку навсегда. Пара не начиналась никогда, и это ловил собственный
харнесс: `chest[type=left]` — UNREACHABLE, ожидаемое состояние `type=single`. Затронуты все
12 блоков семейства (обычный, ловушка, 10 медных). Теперь, пока партнёра нет, целью клика
становится ровно одиночный сундук нужного лица — не «любой», иначе клик, слипшийся с
посторонним сундуком не с той стороны, прошёл бы как идеальный. Вторую половину доводит
вана: `ChestBlock.updateShape` переводит стоящий `single` в `neighbourType.getOpposite()`,
и обе половины становятся двойным сундуком сами. Клетка, ждущая пару, больше не получает
кликов и не держит на себе прицел.

**fix — при включённом Easy Place не открывались контейнеры.** Глушителей оказалось четверо:
своё «Только по схеме» (перед сундуком в схеме пусто, а блок в руке есть), отказ самой
Litematica на занятой клетке, её же «Ограничение размещения» (гасит клик при любом непустом
предмете в руке) и перенос прицела на недостроенную клетку. Совет «повесить хоткей
активации» тут не помогает: Easy Place как раз и вешают на ПКМ, то есть на ту же кнопку.
Новый тумблер «Открывать контейнеры» снимает все четыре, когда игрок целится прямо в
контейнер, не приседает, схема ничего не просит вплотную к нему **в пределах активного
слоя** — и правая кнопка нажата заново, а не удерживается со стройки: зажатая ПКМ
по-прежнему ставит блоки и сундук не распахнёт. «Просит» — строго: клик обязан мочь построить эту клетку. Клетка вне активного слоя не
в счёт (при послойной стройке Litematica вне слоя не ставит), клетка с уже стоящим — пусть
и не тем — блоком тоже (кликом её не поправить, только сломать руками). Без обеих оговорок
сундук в зоне схемы не открывался: клик не строил и не открывал, а умирал. Эндер-сундук распознаётся отдельно: экран он открывает из `useWithoutItem`, а
`getMenuProvider` у него не переопределён и отдаёт null. Пропуск безопасен: вана съедает
такой клик в `useWithoutItem` раньше установки. Поставить блок вплотную к контейнеру, как
и в ване, можно с приседом — решатель для таких якорей его и так форсирует.

**fix — кувшинку нельзя было поставить нигде в мире.** Страховка от заблудившихся
кувшинок (`WaterPlace.strayUse`) гасит ванильный `use()`, который приходит тем же правым
кликом со взглядом игрока. Но «Только по схеме» она применяла без проверки границы
области: за пределами схемы клетка в схеме пуста — и клик отменялся где угодно, лишь бы
активация Easy Place была зажата. А при бинде активации на ПКМ она зажата в момент
любого клика — кувшинка, икра лягушки и прочие `PlaceOnWaterBlockItem` не ставились
вообще. Теперь граница проверяется как в `PlacementGuard`: вне области размещения
ванильный `use()` проходит нетронутым — за схемой это ручная работа игрока.

**fix — каждая поставленная табличка распахивала редактор.** Сервер после установки
таблички зовёт `openTextEdit` и присылает `ClientboundOpenSignEditorPacket` — при стройке
по схеме это выбивает из ритма на каждом указателе. Ответ серверу при этом не обязан
приходить из экрана: `ServerboundSignUpdatePacket` принимается, пока игрок числится
редактором (`playerWhoMayEdit` выставляется до отправки пакета). Теперь свежепоставленная
табличка схемы отвечается сразу текстом из block entity схемы, без экрана — надписи
встают как в схеме сами. «Свежепоставленная» отличается от ручного редактирования по
тексту: у только что поставленной все строки пусты; клик «редактировать» по уже
заполненной открывает экран как в ванили, так что поправить схемную табличку руками
по-прежнему можно.

**fix — подтверждение сервера по номеру пакета вместо окна в 300 мс.** «Сервер ещё не
ответил» определялось таймаутом, поэтому на пинге выше окна второй клик уходил до ответа:
колонна лозы вырастала на клетку дальше схемы, свечей вставало больше, чем просили, а
лишайник получал лишнюю грань. В протоколе для этого есть точный механизм:
`BlockStatePredictionHandler` нумерует предсказания, номер уходит в пакете, а сервер зовёт
`ackBlockChangesUpTo` до всех своих проверок и присылает подтверждение. Теперь клетка
считается подтверждённой, когда подтверждение дошло до её номера, а окно осталось
страховкой на случай, когда его не будет вовсе. На быстром соединении простой схлопывается
до реального RTT. Номер дописывается в запись строго после возврата из клика: записанный
заранее принадлежал бы предыдущему клику, подтвердился бы мгновенно и отключил бы защиту
целиком.

**fix — вечный повтор по клетке, где построено не то.** Побочный результат подтверждения по
номеру — точный признак «сервер отказал»: ответ пришёл, а клетка не изменилась. Раньше
отличить отказ от задержки было нечем, и клетка, в которой стоит не тот блок, получала клик
каждые две секунды до конца стройки (корень — `PlacementUtils.isReplaceable` в malilib,
который почти всегда отвечает «заменяема», из-за чего последняя защита Litematica не
срабатывает). Теперь отказы считаются по клетке, после шестого мод перестаёт в неё кликать и
один раз пишет в чат, куда именно и почему, — вместо того чтобы молча биться в неё вечно.

**fix — удвоённая плита (`type=double`), 68 блоков.** Одним кликом она не получается
никогда, нужен второй по уже стоящей половине. Доводка есть в самой Litematica, но её ветка
исполняется только при неудаче клика: на успешной установке управление уходит на выход
раньше (видно в байткоде 0.28.4). Поэтому плиты молча оставались одинарными, а при
«Пропускать то, что встанет неверно» — просто пропускались. Теперь мод бьёт вторым кликом
сам, по настоящей поверхности плиты, а не по грани клетки (точка, висящая над блоком,
античиту подозрительна), и геометрию проверяет ванильной симуляцией: либо блок ляжет именно
в эту клетку, либо клика не будет вовсе.

**feature — доводка блоков инструментом: медь, грядка, тропинка, свеча на торт.** Ванильные
пары «блок A + предмет → блок B», кроме горшка и обтёсывания, не работали ни одна. Медь:
топор снимает стадию окисления и воск, соты вощат — это ~120 блоков реестра, и схема с
`waxed_*` при обычной меди в руках была непроходима, как и медь, успевшая окислиться за
долгую стройку. Мотыга делает из земли грядку, лопата — тропинку: любая ферма в схеме до сих
пор не строилась. Свеча садится на торт — 17 блоков. Там, где своего предмета в выживании
нет вовсе (свечной торт, грядка, тропинка), сначала ставится заготовка — торт или земля, —
но только если доводящий инструмент есть в инвентаре: иначе в клетке навсегда остался бы
торт вместо свечного торта. Ни одна из доводок не ставит блок в новую клетку — все они
кликают по уже стоящему, поэтому лишний блок мимо схемы положить не могут. Мотыга и лопата
ждут, пока клетка сверху свободна: это ванильное условие обеих операций. Настройка
«Доводить блоки инструментом», включена по умолчанию.

**fix — фонарь молча вставал стоя, когда потолка ещё нет.** `hanging` задаётся установкой,
но не считался ориентацией, поэтому «Пропускать то, что встанет неверно» такую клетку не
отсекало и фонарь оставался стоячим навсегда. При готовом потолке всё было верно — пробел
бил ровно в порядке укладки.

**fix — решатель зря перебирал позы под свойства, которые установкой не задаются.** Режим
компаратора, формы рельса и половина кровати помечены как незадаваемые: перебор по ним не
тратится, ложный «частичный план» больше не появляется. Режим компаратора по-прежнему
доводится отдельной починкой («Циклить состояния»), формы рельса — соседями.

**fix — состояние Easy Place не чистилось при смене мира и измерения.** Счётчик предсказаний
живёт в мире и в новом начинается с нуля, поэтому записи со старыми номерами ждали бы
подтверждения вечно.

**Standalone-мод переименован в Litematica EasyPlaceFixPlus.** `EasyPlaceModule` уступает
модулем при `easyplacefixplus` (прежний id `easyplaceplus` остался вторым вариантом, так что
у кого стоит старый билд — работает по-прежнему), а ключ слота в `SLOT_ORDER` кнопок обновлён
на новое имя вместе с Zone Focus и Ghost Alpha. Вкладка Easy Place получила иконку отдельного
мода: модуль один, знак один.

**Вкладка Easy Place больше не прячется, когда стоит отдельный мод — настройки у них общие.**
Движок в этом случае ведёт он, но крутить опции человек должен из любого экрана, и видеть
там одни значения, а не два разошедшихся набора. Собрать моды друг против друга нельзя, поэтому
связь — рефлексия по одному классу-мосту с фиксированным именем (`compat.EasyPlaceBridge`:
`option(String)`, `saveOwn()`), а логика — в общем `SharedOptions` из `aell-ui`. Fabric держит
моды в одном загрузчике, значит `ConfigBoolean` у соседа — тот же класс malilib, и его объект
уходит прямо в экран: вкладка правит живое значение соседа, без копий и пересылок. На
сохранении значения переносятся к себе и файл соседа тоже пишется, так что снятие любого из
двух модов ничего не сбрасывает. Через мост ходит только список `EP_OPTIONS`: `chatMessages`
у обоих называется одинаково, но у нас это ещё и сообщения поиска по хранилищам.

**fix — при установленном отдельном моде встроенный модуль глушился не до конца.** Обе точки
входа (`MixinEasyPlaceUtils`, `MixinWorldUtils`) поднимали `AutoPlaceState.inHandler()` без
проверки `isActive()`, а правка прицела (`MixinLitematicaRayTraceUtils`) и снятие кэша позиции
смотрели только на `inHandler()`. В итоге поверх установки, которой уже занимался отдельный
мод, наши миксины продолжали править прицел и сбрасывать кэш — два мода тянули один клик.
Теперь `isActive()` спрашивают все четыре места, и это же делает свой тумблер честным мастер-
выключателем: выключен — управление отпущено целиком. В `ContainerClick` проверка отдельного
мода поднята выше тумблера контейнеров: значение тумблера в этом случае живёт у соседа, и
объяснять отказ своим было неверно.

**fix — рельсы вставали не в том положении, что в схеме.** Установкой рельс задаёт только
ось: `BaseRailBlock.getStateForPlacement` смотрит на угол взгляда и кладёт `north_south`
либо `east_west`. Настоящую форму — поворот, подъём, разворот на соседа — блок получает уже
в мире, и делает это сервер (`onPlace` → `RailState.place`); на клиенте эта ветка выходит
первой же строкой по `level.isClientSide`. Поэтому симуляция клика видела только заготовку,
`shape` числился «не задаваемым установкой», перебор угла под рельс не запускался вовсе — и
рельс ложился по той оси, куда игрок случайно смотрел. Ванильный расчёт `RailState` теперь
повторён на чтение (`Rails`, ни одной записи в мир; в 0.0.26 повтор заменён своими правилами с тем
же ответом) и досчитывается поверх симуляции: решатель
знает настоящую форму до клика и подбирает под схему угол взгляда. Затронуты все четыре вида —
обычный, энергетический, нажимной, активирующий; повороты и подъёмы обычного тоже. Где форму
целиком решают соседи, а нужного соседа схема ещё не поставила, клетка откладывается до его
появления — но только пока он вообще может появиться: когда соседство достроено и точной формы
уже не выйдет, рельс ставится как есть, чтобы «Пропускать то, что встанет неверно» не оставило
в пути дыру навсегда. Стоящий рельс не той формы больше не собирает бесполезные клики: заменить
его нельзя, а правый клик по нему ушёл бы в клетку над ним — лишним рельсом поверх пути.
Расчёт сверен с ванилью на 40 048 наборах соседства (`автотесты/TestRails.java`,
дифференциальный прогон против самого `RailState`) — расхождений ноль.


**EN**

**fix — every placed sign popped the edit screen open.** After a sign is placed the server
calls `openTextEdit` and sends `ClientboundOpenSignEditorPacket` — on a schematic build
that breaks the rhythm on every signpost. The answer does not have to come from the
screen: `ServerboundSignUpdatePacket` is accepted as long as the player is registered as
the sign's editor (`playerWhoMayEdit` is set before the packet goes out). A freshly placed
schematic sign is now answered immediately with the text from the schematic's block
entity, no screen — the lettering lands as in the schematic by itself. "Freshly placed" is
told apart from manual editing by the text: a just-placed sign has all lines empty; a
click on an already-written sign opens the screen the vanilla way, so a schematic sign can
still be corrected by hand.

**fix — the server's answer is now read from the packet sequence number instead of a 300 ms
window.** "The server has not answered yet" used to be a timeout, so on a ping above the
window the second click left before the answer: a vine column grew one cell past the
schematic, more candles went in than asked for, and lichen got an extra face. The protocol has
an exact mechanism for this: `BlockStatePredictionHandler` numbers predictions, the number
travels in the packet, and the server calls `ackBlockChangesUpTo` before all of its own checks
and sends the acknowledgement back. A cell now counts as confirmed once the acknowledgement
reaches its number, and the window stays as a fallback for when none arrives at all. On a fast
connection the idle time collapses to the real RTT. The number is written into the record
strictly after the click returns: written in advance it would belong to the previous click,
would read as instantly confirmed, and would switch the whole protection off.

**fix — endless retry on a cell that holds the wrong block.** The acknowledgement also gives
an exact "the server refused" signal: the answer arrived and the cell did not change. There
was no way to tell refusal from delay before, so a cell holding a block other than the
schematic's got a click every two seconds until the end of the build (the root is
`PlacementUtils.isReplaceable` in malilib, which answers "replaceable" for almost anything, so
Litematica's last check never fires). Refusals are now counted per cell; after the sixth the
mod stops clicking there and says once in chat where and why, instead of silently hammering it
forever.

**fix — double slabs (`type=double`), 68 blocks.** A single click never produces one; it takes
a second click on the half already standing. Litematica has that follow-up, but its branch
only runs when the click failed: on a successful placement control leaves earlier (visible in
0.28.4 bytecode). So slabs silently stayed single, and with "Skip what would go in wrong" they
were skipped outright. The mod now sends the second click itself, aimed at the slab's real
surface rather than the cell face (a point hanging above the block looks suspicious to
anticheat), and checks the geometry with vanilla simulation: either the block lands in that
exact cell or no click is sent.

**feature — finishing blocks with tools: copper, farmland, dirt path, candle on cake.** None
of the vanilla "block A + item → block B" pairs worked apart from the flower pot and
stripping. Copper: an axe takes off an oxidation stage and the wax, a honeycomb waxes — about
120 registry blocks, and a schematic asking for `waxed_*` while you hold plain copper was
impassable, as was copper that oxidized during a long build. A hoe turns dirt into farmland, a
shovel into a path: any farm in a schematic simply did not get built. A candle goes onto a cake
— 17 blocks. Where the block has no survival item at all (candle cake, farmland, path) a base
block goes down first — cake or dirt — but only when the finishing tool is in your inventory,
otherwise the cell would keep a cake instead of a candle cake forever. None of these place a
block into a new cell: they all click the block already standing there, so they cannot drop a
stray block off-schematic. The hoe and the shovel wait until the cell above is clear, which is
the vanilla condition for both. New setting "Finish blocks with tools", on by default.

**fix — a lantern silently stood upright when the ceiling was not built yet.** `hanging` is set
at placement but did not count as orientation, so "Skip what would go in wrong" never held
that cell back and the lantern stayed upright forever. With the ceiling in place everything was
already correct — the gap hit exactly in build order.

**fix — the solver wasted poses on properties that placement never sets.** Comparator mode,
rail shapes and bed part are now marked as not-set-at-placement: no probes are spent on them
and the bogus "partial plan" is gone. Comparator mode is still finished by its own fix ("Cycle
states"), rail shapes by their neighbours.

**fix — Easy Place state was not cleared on a world or dimension change.** The prediction
counter lives in the world and starts from zero in a new one, so records holding old numbers
would have waited for their acknowledgement forever.

**fix — double chests never got built.** `ChestBlock.getStateForPlacement` takes `type`
from the world's neighbours, not from the click: `candidatePartnerFacing` wants a chest of
the same block with `type=single` and the same facing already standing next door. The
first half of a pair has no such neighbour, so every probe returned `single`, and
`CHEST_TYPE` counts as set-at-placement — "Skip what would go in wrong" wrote the cell off
forever. The mod's own coverage harness showed it: `chest[type=left]` — UNREACHABLE,
expected state `type=single`. All 12 blocks of the family (regular, trapped, 10 copper
ones). While the partner is missing, the click now aims for exactly a single chest with
the right facing — not "any": a click merging with a foreign chest on the wrong side would
have passed as perfect. Vanilla finishes the pair: `ChestBlock.updateShape` flips the
standing `single` to `neighbourType.getOpposite()` when the second half lands. A cell
waiting for its partner takes no clicks and does not hold the aim.

**fix — containers would not open while Easy Place is on.** Four silencers, not one: our
own "Schematic only" (the cell in front of the chest is empty in the schematic and there
is a block in hand), Litematica's refusal on an occupied cell, its Placement Restriction
(kills the click with any non-empty hand), and the aim transfer onto an unbuilt cell.
Binding the activation hotkey does not help — Easy Place gets bound to RMB, the same
button. The new "Open containers" toggle lifts all four when the player aims straight at
the container, is not sneaking, the schematic asks for nothing buildable against that face
(a cell outside the active layer or one holding a wrong real block does not count — a
click could not build it anyway), and the right button was pressed anew rather than held
from building: held RMB still places blocks and never pops a chest open. The ender chest
is recognized by name: it opens its screen from `useWithoutItem` and its
`getMenuProvider` returns null. The pass-through is safe: vanilla eats the click in
`useWithoutItem` before any placement. Placing a block against a container still works the
vanilla way, by sneaking — the solver forces sneak on such anchors anyway.

**fix — lily pads could not be placed anywhere in the world.** The stray-lily-pad guard
(`WaterPlace.strayUse`) kills the vanilla `use()` that arrives on the same right click
with the player's real look. But it applied "Schematic only" with no boundary check:
outside the schematic the cell is empty in the schematic — so the click died anywhere in
the world as long as the activation key was held. With the activation on RMB it is held
during every click — lily pads, frogspawn, every `PlaceOnWaterBlockItem` never placed.
The boundary is now checked the same way as in `PlacementGuard`: outside the placement
the vanilla `use()` passes untouched — out there it is the player's manual work.

**The standalone mod is now Litematica EasyPlaceFixPlus.** `EasyPlaceModule` steps aside for
`easyplacefixplus`, still recognising the earlier `easyplaceplus` id, and the button slot key
follows the new name here, in Zone Focus and in Ghost Alpha. The Easy Place tab now carries the
standalone mod's icon — one module, one mark.

**The Easy Place tab no longer hides when the standalone mod is installed — the settings are
shared.** The engine is run by the standalone one, but the options can be turned from either
screen and show the same values. The mods cannot be built against each other, so they meet over
one bridge class with a fixed name (`compat.EasyPlaceBridge`), and since Fabric keeps every mod
in a single class loader, the sibling's `ConfigBoolean` is the same malilib class — the tab edits
its live value directly. Saving writes both config files, so removing either mod keeps every
setting. Only `EP_OPTIONS` crosses the bridge: `chatMessages` shares the name but not the meaning.

**fix — the built-in module was only half silenced with the standalone mod installed.** Both
handler entries raised `inHandler()` without checking `isActive()`, and the aim rewrite and the
position-cache reset looked at `inHandler()` alone, so our mixins kept steering a placement the
standalone mod was already driving. All four places check `isActive()` now, which also makes the
module's own toggle a real master switch.

**fix — rails came out in a different orientation than the schematic.** Placement only sets a
rail's axis: `BaseRailBlock.getStateForPlacement` reads the look angle and writes `north_south`
or `east_west`. The real shape — the turn, the slope, the swing towards a neighbour — is given
to the block once it is in the world, and it is the server that does it (`onPlace` →
`RailState.place`); on the client that branch returns on its first line, at `level.isClientSide`.
So the click simulation only ever saw the blank, `shape` counted as "not set by placement", the
look-angle sweep never ran for a rail at all — and the rail landed on whichever axis the player
happened to face. The vanilla `RailState` calculation is now mirrored read-only (`Rails`, not a
single write to the world; in 0.0.26 the mirror was replaced by the mod's own rules giving the same
answers) on top of the simulation: the solver knows the real shape before the
click and picks the look angle that matches the schematic. All four kinds are affected — plain,
powered, detector, activator — and the plain rail's turns and slopes as well. Where the shape is
decided by neighbours alone and the schematic has not placed the needed one yet, the cell waits
for it — but only while it can still arrive: once the neighbourhood is complete and the exact
shape is out of reach, the rail goes in as it comes, so that "skip what would be placed wrong"
does not leave a permanent hole in the track. A standing rail with the wrong shape no longer
collects useless clicks: it cannot be replaced, and a right click on it would land in the cell
above it as a stray rail over the track. The calculation is checked against vanilla on 40,048
neighbourhoods (`автотесты/TestRails.java`, a differential run against `RailState` itself) —
zero divergences.

## 0.0.10+26.2 (не выпущено, часть 1)

**RU**

**fix — кувшинки и икра лягушки не вставали на воду.** У этих блоков предмет особый
(`PlaceOnWaterBlockItem`): его `useOn` всегда отдаёт PASS, то есть клик по блоку для них
пустой в принципе — и ванильный, и присланный Easy Place. Работает только `use`: сервер
сам ведёт луч из глаз игрока с `Fluid.SOURCE_ONLY`, берёт клетку воды, в которую попал,
и ставит блок над ней. Litematica же умеет только кликать по клетке, поэтому Easy Place
честно слал клик, считал позицию сделанной и шёл дальше — на воде не появлялось ничего.
Теперь такая клетка обрабатывается своим путём: подбирается угол взгляда, при котором
серверный луч попадёт ровно в клетку воды под целью, и `use` отправляется сам. Каждая
проба проверяется тем же лучом, что ведёт сервер, — промах по соседней колонне воды
отсеивается до отправки, а не всплывает кувшинкой не там. Взгляд берётся из пакета, так
что «Не поворачивать игрока» действует и здесь: без поворота клетка ждёт своей очереди.
Опора проверяется по-ванильному (`canSurvive`), поэтому лёд и прочие подставки из тега
`supports_lily_pad` работают наравне с водой, а пока вода в клетке ниже ещё не налита,
позиция просто ждёт. Заодно погашен ванильный `use`, который прилетал тем же правым
кликом уже с настоящим взглядом игрока и сеял кувшинки мимо схемы: он пропускается,
только если и сам попадает ровно в незаполненную клетку схемы.

**fix — цветы, лозы, саженцы и лишайник не ставились: Easy Place промахивался мимо их
голограмм.** Litematica ведёт луч по контурам самих голограмм; у цветка это крестик, у
лозы и лишайника — тонкая пластина. Луч промахивался мимо контура и попадал в блок за
голограммой — почти всегда уже построенную часть той же схемы, — и Litematica отвечала
«клетка уже как в схеме», не ставя ничего. Свечи и цепи в пустоте работали: за ними
просто ничего нет. Теперь, если луч промахнулся мимо контура голограммы, а клетка, куда
он попал, работы не требует (построена или предмета для неё не существует), целью
становится первая по лучу клетка, которую схема просит заполнить. Правило геометрическое
и настроек не имеет: только свободные в мире клетки, только с существующим предметом
(верх двери и пиона пропускается — ловится низ), не дальше настоящего блока под
прицелом — сквозь стены не ставится, и только внутри обработчика Easy Place. Настройка
«Наводиться на тонкие блоки в воздухе» удалена: её случай покрыт этим же правилом.

**fix — светящиеся ягоды, лозы и свисающий мох не ставились вовсе.** Корень — отравленный
кеш предметов Litematica: список материалов спрашивает предмет для состояния «без мира»,
у «тела» лозы (`cave_vines_plant`) своего предмета нет — `asItem()` даёт пустоту, и она
кешируется по состоянию навсегда. Easy Place потом достаёт из кеша пустоту и молча гасит
позицию. Теперь предмет для колонны отдаётся в обход кеша — предмет её головы (светящиеся
ягоды); именно так это решает и EasyPlaceFix своим миксином на `GrowingPlantBlock`. Сам
клик сверен с ванильной Litematica и EasyPlaceFix: по клетке цели, нетронутым, без приседа
и подмен. Верхняя клетка сама превращается в тело, когда под ней появляется следующая.
Модуль добавляет только страховки: без опоры позиция ждёт своей очереди; второй клик по клетке не уходит до
подтверждения сервером; голова, стоящая там, где схема просит тело, считается готовой
клеткой — прицел уводится к первой действительно пустой клетке колонны, так что лозу
можно наращивать, целясь в любое место её голограммы. Работает всегда: светящиеся ягоды,
плакучие и вьющиеся лозы, ламинария, свисающий мох, свисающие корни.

**feat — полки заполняются, статуи позируют.** Резная книжная полка: шесть слотов — это
шесть свойств состояния, а какая книга в каком слоте, знает block entity схемы; книга
вставляется кликом в свою шестую часть грани (геометрия `getHitSlot`), кликаем только с
книгой в руке — пустая рука вынимает. Полка с предметами: три слота по третям грани,
содержимое только в block entity; клик предметом кладёт его, при занятом слоте меняет
местами — неверный предмет исправляется одним кликом; запитанную редстоуном полку не
трогаем. Тумблер «Заполнять полки» (вкл.) в «Состоянии через взаимодействие». Статуя
медного голема: установка всегда даёт standing, любой клик без топора циклит позу —
докликиваем пустой рукой до позы из схемы (топором не кликаем: он снимает окисление).
Свойство `SIDE_CHAIN_PART` полок добавлено в «не задаётся установкой» — решатель больше
не бракует полки из-за него.

**fix — отметки поиска не висят от выгруженной схемы.** Когда все размещения убраны,
пересчёт выходил раньше, чем поиск по хранилищам узнавал про пустую страницу, — старый
набор предметов оставался, и метки из прошлой схемы (или другого измерения) продолжали
висеть над сундуками. Теперь пустой пересчёт тоже синхронизирует поиск: набор пустеет,
метки гаснут, сам поиск остаётся включённым и подхватит следующую схему.

**fix — дверь и люк доводятся до состояния схемы сразу.** `open` не задаётся ни одним
кликом установки — дверь всегда встаёт закрытой, и её открывает отдельный клик. Этот клик
съедал кеш Litematica «сюда уже кликали»: позиция считалась сделанной, и дверь оставалась
закрытой до следующего прохода прицелом. Теперь клетка, которой ещё нужна починка
состояния, из кеша исключается — доводка происходит следующим тиком. Если прицел уже ушёл
дальше, клетку дожимает очередь: до 16 позиций, только пока Easy Place в работе, только по клеткам
в ванильной дистанции взаимодействия, на виду и не за стеной, с той же паузой «Ждать
подтверждения».

**feat — обтёсанные брёвна строятся обычными.** Обтёсанное бревно в мире получают топором
из обычного, своего рецепта установки у него нет. Если обтёсанный блок есть — ставится он;
если нет, а обычный аналог и топор есть — ставится обычный (подбор клика идёт под его ось)
и следующим кликом обтёсывается топором, как свеча ставится и поджигается огнивом. Таблица
пород берётся из самой игры, поэтому модовые деревья работают наравне с ванильными. Список
материалов не затронут. Тумблер «Обтёсывать брёвна» (вкл.) — в разделе «Состояние через
взаимодействие», рядом с огнивом и вёдрами.

**feat — раздел «Сервер и античит», собранный по реальным проверкам.** Разобраны GrimAC,
Vulcan и AntiLitematica — что именно они проверяют у установки блоков: скорость
(fastplace), аномальные hit-векторы (протокол Easy Place кодирует поворот блока
смещениями точки попадания на +2 за свойство — ванильный клиент так не кликает никогда),
расхождение взгляда и клика (RotationPlace / «no raytrace»), лишние пакеты поворота
(timer) и дистанцию. Ответ мода по пунктам: **«Блоков в секунду»** (1…20, по умолч. 10 —
вдвое быстрее зажатой правой кнопки и незаметно для большинства античитов; клики мимо
схемы не ограничиваются); **«Считать сервер ванильным»** (выкл.) теперь не только
подбирает честный клик, но и пересчитывает закодированную протоколом точку попадания на
пересечение луча взгляда с гранью; **«Не поворачивать игрока»** (выкл.) убирает и рывки
взгляда, и лишние пакеты поворота. Клики-починки идут только по клеткам в ванильной
дистанции взаимодействия, впереди и не за стеной, а их точка попадания лежит на луче
взгляда, как у настоящего клика.

**feat — «Отладка Easy Place» (выкл.).** Каждое решение конвейера — серой строкой в чат:
куда пришёлся клик, что просит схема, почему клик отменён или пропущен, чем ответил
`handleEasyPlace`. Включается, когда что-то не ставится: по строкам видно, какая именно
проверка съедает клик. Повторы схлопываются.

**change — в настройках остались только настоящие выборы.** У починок, без которых Easy
Place просто не работает, тумблеров больше нет: точное состояние и сторона, точное
количество, помощь прицела, лозы, горшки с растениями, защита полок работают всегда, пока
включён модуль. Убраны «Не превышать количество», «Поворот для верной стороны», «Ставить
точно как в схеме», «Не заполнять полки», «Сажать растения в горшки»; «Задержка между
блоками, мс» (0…1000) заменена понятной шкалой «Блоков в секунду» (1…20). Остались:
главный тумблер, «Пропускать то, что встанет неверно», шесть починок предметом
(они тратят предметы и прочность), темп и два переключателя под античит.

**feat — «Только по схеме» (вкл.): страховка от лишних блоков.** Litematica иногда ставит
лишний блок рядом со схемным — её трассировка возвращает «мимо», клик падает в ваниль, и
обычный правый клик по грани уже стоящего забора ставит соседний. Теперь, пока работает
Easy Place, внутри области размещения не поставить ничего, чего не просит клетка: и такие
промахи, и случайные ручные клики не тем блоком гаснут до отправки. Клетки вне области
схемы не трогаются. В подсказке честно сказано: спорный клик отменяется молча, и в редких
случаях страховка может съесть нужный — тогда её выключают.

**fix — поиск по хранилищам гас, когда страница собрана.** Признаком «поиск включён» был
непустой набор отмеченных предметов, а «Пропускать имеющееся» вычищает из этого набора всё,
чего в инвентаре уже хватает. Собрал страницу — набор опустел, и поиск считался выключенным:
на следующей странице ничего не отмечалось, приходилось выходить из сундука и снова жать `U`.
Теперь включённость — отдельный флаг. Отметки гаснут (искать нечего), но поиск продолжает
следовать за страницами и загорается сам, как только на странице снова есть что искать.
Выключают только повторное `U` на той же странице и `Ctrl+U`.

**change — `U` и `Ctrl+U` работают при открытом контейнере.** Контекст клавиши — `ANY`, как у
листания страниц: включить, выключить или сбросить поиск можно не выходя из сундука. В прочих
экранах (настройки, табличка, наковальня) и при фокусе в текстовом поле клавиши не срабатывают.


**EN**

**fix — lily pads and frogspawn would not go onto water.** Their item is a special one
(`PlaceOnWaterBlockItem`): its `useOn` always returns PASS, so a click on a block does
nothing for them at all — neither a vanilla one nor the one Easy Place sends. Only `use`
works: the server itself leads a ray from the player's eyes with `Fluid.SOURCE_ONLY`,
takes the water cell it lands in and places the block above it. Litematica can only click
a cell, so Easy Place dutifully sent the click, marked the position done and moved on —
and nothing appeared on the water. Such a cell now takes its own path: the look angle is
picked so that the server's ray lands exactly in the water cell under the target, and
`use` is sent directly. Every candidate is checked with the same ray the server leads, so
a miss into a neighbouring water column is filtered out before sending instead of
surfacing as a lily pad in the wrong place. The angle travels in the packet, so "Don't
turn the player" applies here too: without a turn the cell waits its turn. Support is
checked the vanilla way (`canSurvive`), so ice and the rest of the `supports_lily_pad` tag
work just like water, and while the water below has not been poured yet the position
simply waits. The vanilla `use` that arrived on the same right click with the player's
real look — scattering lily pads off-schematic — is suppressed as well: it is let through
only when it lands exactly in an unfilled schematic cell itself.

**fix — flowers, vines, saplings and lichen would not place: Easy Place missed their
ghost.** Litematica leads the ray by the outlines of the ghosts themselves; a flower's
outline is a cross of two planes, a vine's or lichen's a thin plate. The ray slipped past
the outline and hit the block behind — almost always an already-built part of the same
schematic — and Litematica answered "this cell already matches" and placed nothing. Candles
and chains in the open worked: there is simply nothing behind them. Now, when the ray misses
a ghost's outline and the cell it did hit needs no work (already built, or no item exists
for it), the target becomes the first cell along the ray the schematic asks to fill. The
rule is geometric and has no setting: empty-in-world cells only, only ones an item exists
for (a door's or peony's upper half is skipped — the lower one is caught), never beyond the
real block under the crosshair — nothing goes through walls — and only inside the Easy
Place handler. The "Aim at thin blocks in the air" setting is gone: its case is covered by
this same rule.

**fix — glow berries, vines and hanging moss would not place at all.** The root is a
poisoned Litematica item cache: the material list asks for a state's item "without a world",
the body of a vine (`cave_vines_plant`) has no item of its own — `asItem()` yields empty,
and that empty is cached per state forever. Easy Place then pulls the empty out of the cache
and silently kills the position. The column's item is now served past the cache — its head's
item (glow berries); EasyPlaceFix solves it the same way with its `GrowingPlantBlock` mixin.
The click itself is checked against vanilla Litematica and EasyPlaceFix: the target cell,
untouched, no sneaking, no rewrites. The cell above turns into a body by itself once the
next one appears below it. The module only adds the guards: without support the position waits its turn; no second click goes to
a cell before the server confirms the first; and a head standing where the schematic wants
a body counts as a finished cell — the aim moves on to the first genuinely empty cell of
the column, so a vine can be grown by aiming anywhere along its ghost. Always on: glow
berries, weeping and twisting vines, kelp, hanging moss, hanging roots.

**feat — shelves get filled, statues get posed.** A chiseled bookshelf's six slots are six
state properties, and which book goes where is known by the schematic's block entity; a book
is inserted by clicking its sixth of the face (the `getHitSlot` geometry), and only ever with
a book in hand — an empty hand pulls books out. An item shelf has three slots by thirds of
the face, contents live in the block entity only; clicking with an item puts it in, an
occupied slot swaps — a wrong item is fixed in one click; redstone-powered shelves are left
alone. Toggle "Fill shelves" (on) in "State by interaction". A copper golem statue always
places standing, and any non-axe click cycles the pose — we click it with an empty hand until
it matches the schematic (never with an axe: that scrapes oxidation). The shelf's
`SIDE_CHAIN_PART` property is now on the "not set by placement" list, so the solver no longer
rejects shelves over it.

**fix — search marks no longer linger from an unloaded schematic.** With every placement
removed, the recount returned before the storage search learned the page was empty — the old
item set stayed, and marks from the previous schematic (or another dimension) kept hanging
over chests. An empty recount now syncs the search too: the set empties, the marks go out,
and the search itself stays armed for the next schematic.

**fix — doors and trapdoors reach the schematic state at once.** `open` cannot be set by any
placement click, so a door always goes in closed and a separate click opens it. That click
was eaten by Litematica's "already clicked here" cache: the position counted as done and the
door stayed closed until the crosshair came back. A cell that still needs a state fix is now
excluded from that cache, so the next tick finishes it. If the crosshair has already moved
on, a queue finishes the job: up to 16 positions, only while Easy Place is running, only
for cells within vanilla interaction range, in view and not behind a wall, with the same
"wait for confirmation" pause.

**feat — stripped logs are built from plain ones.** A stripped log is made with an axe; it
has no placement recipe of its own. If you have the stripped block it is used as before; if
you do not, but have the plain one and an axe, the plain block is placed (the click is
solved for its axis) and stripped with the axe on the next click, the same way a candle is
placed and then lit. The wood table comes from the game itself, so modded trees work like
vanilla ones. The material list is untouched. Toggle: "Strip logs" (on), in the "State by
interaction" section next to the flint-and-steel and bucket fixes.

**feat — a "Server and anticheat" section built around real checks.** GrimAC, Vulcan and
AntiLitematica were studied for what exactly they verify on block placement: speed
(fastplace), abnormal hit vectors (the Easy Place protocol encodes block facing as +2
offsets in the hit position — no vanilla client ever clicks like that), look/click mismatch
(RotationPlace / "no raytrace"), extra rotation packets (timer) and reach. The mod's answer,
point by point: **"Blocks per second"** (1…20, default 10 — twice a held right-click and
invisible to most anticheats; clicks outside the schematic are never throttled); **"Treat
the server as vanilla"** (off) now also rewrites protocol-encoded hit positions to the
intersection of the view ray with the clicked face; **"Never turn the player"** (off)
removes both the view snaps and the extra rotation packets. Fix-up clicks only go to cells
within vanilla interaction range, in front of the player and not behind a wall, and their
hit position lies on the view ray like a real click's.

**feat — "Easy Place debug" (off).** Every pipeline decision as a grey chat line: where the
click landed, what the schematic wants, why the click was cancelled or passed, what
`handleEasyPlace` returned. Turn it on when something does not place — the lines show which
check eats the click. Repeats are collapsed.

**change — only real choices are left in the settings.** Fixes without which Easy Place
simply does not work have no toggles anymore: exact state and facing, exact counts, aim
help, vines, potted plants and shelf protection are always on while the module is enabled.
Removed: "Do not overshoot the count", "Turn for the right facing", "Match the schematic
state", "Do not fill shelves", "Pot the plants"; "Delay between blocks, ms" (0…1000) is
replaced by a plain "Blocks per second" scale (1…20). What remains: the master switch,
"Skip what would go in wrong", six item-based fixes (they consume items and durability),
the pace and the two anticheat switches.

**feat — "Schematic only" (on): a guard against stray blocks.** Litematica sometimes puts
an extra block next to a schematic one — its trace comes back empty, the click falls through
to vanilla, and a plain right click on the face of an existing fence places a neighbour. Now,
while Easy Place is working, nothing the cell does not ask for can be placed inside the
placement area: both such misses and accidental clicks with the wrong block die before being
sent. Cells outside the schematic area are untouched. The tooltip says it honestly: a
disputed click is cancelled silently, and in rare cases the guard can eat a needed one —
that is when you turn it off.

**fix — the storage search switched itself off once a page was collected.** "Search is on" was
inferred from the marked-item set being non-empty, and "Skip items in inventory" strips from that
set everything the inventory already covers. Collect the page and the set went empty, so the
search counted as off: the next page marked nothing and you had to leave the chest and press `U`
again. Being on is now a flag of its own. The marks go out (nothing left to look for), but the
search keeps following pages and lights up again as soon as a page has something to find. Only a
second `U` on the same page and `Ctrl+U` turn it off.

**change — `U` and `Ctrl+U` work with a container open.** The keybind context is `ANY`, same as
the page keys: the search can be armed, toggled and cleared without leaving the chest. In other
screens (settings, sign, anvil) and while a text field has focus the keys do nothing.

---

## 0.0.9+26.2 (hotfix)

**RU**

**fix — горшки с растениями строятся в два приёма.** Своего предмета у горшка с растением нет,
а `getCloneItemStack` у непустого горшка отдаёт содержимое, поэтому Litematica просила для
клетки с `potted_dandelion` одуванчик — и Easy Place сажал его в землю вместо горшка. Когда
же горшок уже стоял, клетка была занята, и штатная проверка на заменяемость гасила позицию
целиком: посадить в него было нечего.

Теперь пока клетка пуста, требуемым предметом внутри обработчика Easy Place подставляется
пустой горшок — он и ставится первым кликом; в уже стоящий пустой горшок следующим кликом
сажается растение из руки, хотбара или инвентаря. Клик по горшку идёт **без приседа**: с
приседом игра пропустила бы действие блока и попыталась поставить цветок на горшок. Между
кликами работает то же ожидание подтверждения сервером, что и у прочих починок состояния.
Тумблер «Сажать растения в горшки» (вкл.). Список материалов не затронут — Litematica и так
считает горшок и растение двумя предметами.

**fix — наши собственные клики по уже стоящему блоку** (огниво, ведро, пустая рука, растение
в горшок) больше не проходят через разбор установки: позиция, грань, предмет и поза у них
посчитаны здесь же, и переписывать их было нечем, кроме как во вред.


**EN**

**fix — potted plants are built in two steps.** A potted plant has no item of its own, and
`getCloneItemStack` on a full pot returns its contents, so Litematica asked for a dandelion on
a `potted_dandelion` cell — and Easy Place planted it in the ground instead of placing a pot.
And once a pot stood there the cell was occupied, so the stock replaceable check killed the
position outright: there was nothing to put into it.

Now, while the cell is empty, the required item inside the Easy Place handler is swapped for
an **empty pot**, which goes down on the first click; the next click puts the plant from the
hand, hotbar or inventory into the pot that now stands there. The click on the pot is sent
**without sneaking** — sneaking would skip the block's use action and try to place the flower
on top of the pot. Between the two clicks the same server confirmation wait applies as for the
other state fixes. Toggle: "Pot the plants" (on). The material list is untouched — Litematica
already counts the pot and the plant as two items.

**fix — our own clicks on a block that already stands** (flint and steel, bucket, bare hand,
plant into a pot) no longer go through placement analysis: their position, face, item and
pose are worked out right here, so rewriting them could only do harm.

## 0.0.8+26.2 (hotfix)

**RU**

**Баннеры с узорами больше не ставятся вперемешку.** Узор живёт не в состоянии блока,
а в компонентах предмета, поэтому у трёх баннеров одного цвета состояние одинаковое.
Litematica строит требуемый предмет без данных блока (`includeData = false`) и кеширует
его по одному состоянию, а слот в инвентаре ищет без учёта компонентов — в руку попадал
первый баннер нужного цвета, и узор выходил случайный.

Теперь Easy Place берёт требуемый предмет вместе с данными блока и сравнивает именно те
компоненты, которые задают его тождество: узор баннера, черепки декорированного горшка,
профиль головы игрока. Нужный баннер берётся из хотбара сменой слота или из инвентаря;
если такого узора у игрока нет, позиция **пропускается** (тумблер «Пропускать то, что
встанет неверно») — чужой узор не встанет. Имя предмета не сравнивается: переименованный
баннер с тем же узором для стройки годится.

Список материалов и остальная Litematica работают как раньше — подмена действует только
внутри обработчика Easy Place.


**EN**

**Patterned banners are no longer placed at random.** A banner's pattern lives in the item
components, not in the block state, so three banners of one colour share the same state.
Litematica builds the required item without block data (`includeData = false`), caches it
per block state, and searches the inventory ignoring components — the first banner of the
right colour ended up in hand and the pattern came out random.

Easy Place now takes the required item together with the block data and compares exactly the
components that identify it: banner patterns, decorated pot sherds, player head profile. The
right banner is taken from the hotbar by switching slots, or from the inventory; when the
player has no banner with that pattern the position is **skipped** ("Skip what would go in
wrong") instead of being filled with the wrong one. The item name is not compared, so a
renamed banner with the same pattern still counts.

The material list and the rest of Litematica are untouched — the substitution only applies
inside the Easy Place handler.

## 0.0.18+26.2

**RU**

**«Экономные внутренние грани» во вкладке Ghost Alpha — видеопамять перестаёт расти объёмом
схемы.** Штатный режим «Внутренние грани» Litematica отключает отсечение граней целиком: в меш
попадают все шесть граней каждого блока, включая полностью замурованные, у жидкостей — скрытые
грани между слоями, а у оверлея пропадает отсечение замурованных позиций. У большой плотной
схемы это гигабайты видеопамяти — геометрия растёт кубом (объёмом), а не квадратом
(поверхностью).

Новый режим (вкл. по умолчанию, дочерний тумблер под «Внутренними гранями») оставляет
принудительные грани только на стыке РАЗНЫХ блоков: границы материалов и структура сквозь
полупрозрачные голограммы читаются как раньше, а внутренность однородных массивов отсекается по
обычным ванильным правилам. То же для жидкостей и оверлея. При установленном отдельном моде
Ghost Alpha режимом владеет он (у него та же настройка своей вкладкой).


**EN**

**"Lean inner sides" in the Ghost Alpha tab — video memory no longer grows with the
schematic's volume.** Litematica's stock "inner sides" mode disables face culling entirely:
every face of every block is meshed, including fully buried ones, fluids mesh their hidden
inter-layer faces, and the overlay loses its buried-position culling. On a large dense
schematic that is gigabytes of VRAM — geometry grows with the cube (volume), not the square
(surface).

The new mode (on by default, a child toggle under "Inner sides") keeps forced faces only where
two DIFFERENT blocks meet: material boundaries and the structure seen through translucent
ghosts read the same as before, while the interior of uniform volumes is culled by the usual
vanilla rules. Same for fluids and the overlay. When the standalone Ghost Alpha mod is
installed it owns the mode instead (it carries the same setting on its own tab).

## 0.0.17+26.2

**RU**

**Оригинальный EasyPlaceFix теперь вытесняется, а не «уживается».** Пока наш модуль Easy Place
включён, главный тумблер EasyPlaceFix (`enableFix`) принудительно держится выключенным:
Litematica работает своим штатным потоком, и все исправления установки делает Material HUD —
двум ротационным спуферам в одном клике больше неоткуда взяться. При выключении нашего модуля
его собственным тумблером чужой тумблер возвращается, как был, и EasyPlaceFix продолжает
работать сам (в чат приходит уведомление в обе стороны).

Страховка из 0.0.15–0.0.16 (пропуск/гашение чужих «инженерных» кликов и отложенная установка)
остаётся: она срабатывает, если у будущей версии EasyPlaceFix переедет поле конфига и
вытеснение не сможет его найти.


**EN**

**The original EasyPlaceFix is now displaced rather than "coexisted with".** While our Easy
Place module is on, EasyPlaceFix's master toggle (`enableFix`) is force-held off: Litematica
runs its stock flow and Material HUD does all the placement fixes — there is no longer any way
for two rotation spoofers to collide in one click. When our module is switched off with its own
toggle, the foreign toggle is put back the way we found it and EasyPlaceFix carries on by
itself (a chat notice is shown both ways).

The 0.0.15–0.0.16 safety net (passing/cancelling foreign engineered clicks and the deferred
placement) stays in place: it kicks in if a future EasyPlaceFix version moves its config field
and the displacement cannot find it.

## 0.0.16+26.2

**RU**

**Потолочное точило без опоры больше не оставляет лишнее точило на клетку выше.** Сторонний
EasyPlaceFix ставит потолочные блоки кликом по нижней грани блока-опоры над целью. Если опоры
ещё нет (в ванили точило её и не требует), клик приходится в воздушную клетку — сервер считает
её заменяемой и ставит точило прямо в неё, над целью; следующий клик находил «опору» в виде
этого лишнего точила и уже корректно ставил блок на место.

Правило пропуска чужих кликов (0.0.15) уточнено: клик пропускается, только если блок ляжет в
клетку, которую схема просит заполнить. Клик в заменяемую клетку, где схема пуста, гасится, а
если схема просит этот блок в соседней клетке за гранью клика — он ставится нашим решателем
отложенным кликом пару тиков спустя, когда EasyPlaceFix уже снял свою блокировку пакетов
поворота (флаг блокировки читается рефлексией, только на чтение; без стороннего мода отложка
опирается на выдержку по тикам).


**EN**

**A ceiling grindstone with no support no longer leaves an extra grindstone one cell above.**
The third-party EasyPlaceFix places ceiling blocks by clicking the bottom face of the support
block above the target. When that support does not exist yet (vanilla grindstones need none),
the click lands in an air cell — the server treats it as replaceable and puts the grindstone
right there, above the target; the next click then found "support" in that stray grindstone and
placed the block correctly underneath.

The foreign-click pass-through rule (0.0.15) is now stricter: a foreign click is passed through
only when the block would land in a cell the schematic wants filled. A click into a replaceable
cell where the schematic is empty is cancelled, and when the schematic wants this block in the
adjacent cell behind the clicked face, our own solver places it with a deferred click a couple
of ticks later, once EasyPlaceFix has released its rotation-packet lock (the lock flag is read
via reflection, read-only; without the third-party mod the deferral simply relies on the tick
delay).

## 0.0.15+26.2

**RU**

**Точило больше не встаёт криво и не улетает на клетку выше при установленном стороннем
EasyPlaceFix.** На серверах без Carpet сторонний EasyPlaceFix сам подменяет Easy Place
Litematica и ставит блоки собственным заранее просчитанным кликом, а на время клика
переписывает все исходящие пакеты поворота на свой угол. Наш фильтр перехватывал этот клик и
переделывал его по-своему; свой угол взгляда мы дослать не могли (его затирал EasyPlaceFix), и
на сервер уходил клик по клетке цели с горизонтальным взглядом — точило вставало настенным и не
той стороной, а из-за рассинхрона повторный клик ставил второе точило на клетку выше.

Теперь чужие просчитанные клики распознаются (EasyPlaceFix шлёт свой подкласс
`BlockHitResult`) и пропускаются без изменений: точило ставит сам EasyPlaceFix, как и
задумано его автором. Без стороннего мода наш собственный расчёт (0.0.14) работает как раньше.


**EN**

**Grindstones no longer land sideways or one cell up when the third-party EasyPlaceFix is
installed.** On non-Carpet servers the standalone EasyPlaceFix replaces Litematica's Easy Place
entirely and places blocks with its own pre-computed click, rewriting every outgoing rotation
packet to its own angle for the duration. Our click filter intercepted that click and redid it
its own way; our look angle could not reach the server (EasyPlaceFix overwrote it), so the
server saw a click on the target cell with a horizontal view — the grindstone came out
wall-mounted the wrong way, and the resulting desync made the follow-up click place a second
grindstone one cell above.

Foreign engineered clicks are now recognised (EasyPlaceFix sends its own `BlockHitResult`
subclass) and passed through untouched: the grindstone is placed by EasyPlaceFix itself, the
way its author intended. Without the third-party mod our own direct computation (0.0.14) works
as before.

## 0.0.14+26.2

**RU**

**Точило теперь встаёт в воздухе, в том положении, что в схеме.** В ванили точило вообще не
требует опоры (`canSurvive` у него всегда истина) — опору «искал» мод: сторона крепления у
таких блоков берётся из первого подходящего направления взгляда, а при клике по соседнему
блоку игра принудительно ставит первым направление от нажатого блока к цели, поэтому точило
липло к тому, по чему щёлкнули.

Теперь для блоков с креплением (точило, рычаг, кнопка) клик считается напрямую, а не подбором:
щёлкаем по самой клетке цели и подставляем ровно тот угол взгляда, при котором игра выберет
нужную сторону — вниз для напольного, вверх для потолочного, вдоль стены для настенного. Опора
при этом не нужна: точило встаёт в пустоте с правильной стороной и поворотом. Расчёт
проверяется той же ванильной `getStateForPlacement`, так что если состояние не совпало,
позиция как раньше пропускается, а не ставится криво.

Рычагу и кнопке опора нужна по правилам игры, их в пустоте поставить нельзя — они и дальше
пропускаются, пока не появится блок, к которому крепиться.


**EN**

**Grindstones now go up in mid-air, in the orientation the schematic asks for.** Vanilla does not
require support for a grindstone at all (its `canSurvive` is always true) — it was the mod that
"looked for support": the attach face of such blocks comes from the first suitable look
direction, and when clicking a neighbouring block the game forces the direction from the clicked
block towards the target first, so the grindstone stuck to whatever was clicked.

For attachable blocks (grindstone, lever, button) the click is now computed directly instead of
searched: we click the target cell itself and supply exactly the look angle at which the game
picks the wanted side — down for floor, up for ceiling, along the wall for wall. No support is
needed: the grindstone lands in empty space with the right face and rotation. The result is still
checked against vanilla's own `getStateForPlacement`, so if the state does not match the position
is skipped as before rather than placed wrong.

Levers and buttons do need support by the game's rules and cannot be placed in mid-air — those
are still skipped until the block they attach to exists.

## 0.0.13+26.2

**RU**

**Полки с содержимым переехали в саму проверку.** Отдельной клавиши больше нет: отметка полок
включается тумблером «Полки с содержимым» в списке проверки, рядом с «Не хватает», «Не тот
блок» и остальными, и работает вместе с проверкой по `Ctrl+B`. Радиус и лимит отметок общие с
проверкой блоков — своих настроек у полок больше нет, кроме «Обводить и пустые полки».


**EN**

**Shelves with items moved into the check itself.** There is no separate key any more: shelf
marking is turned on by the "Shelves with items" toggle in the check list, next to "Missing",
"Wrong block" and the rest, and it runs together with the check on `Ctrl+B`. Radius and the
marker cap are shared with the block check — shelves have no settings of their own beyond
"Also outline empty shelves".

## 0.0.12+26.2

**RU**

**Точило (и всё, что крепится к блоку) больше не встаёт куда попало.** Сторона крепления у
таких блоков берётся из первого подходящего направления взгляда, а при клике по соседнему
блоку ванила принудительно ставит первым направление от нажатого блока к цели — то есть
блок «прилипает» к тому, по чему щёлкнули. Решатель это учитывал, но если нужного крепления
не добиться вообще (обычно потому, что блок-опора из схемы ещё не построен), он ставил
ближайший похожий вариант. Теперь такая позиция **пропускается**, а не ставится криво:
вернитесь к ней, когда опора появится. Выключатель — «Пропускать то, что встанет неверно».

**Полки больше не собирают блоки при стройке.** Полка забирает предмет из руки по правому
клику, поэтому Easy Place рядом с ней ронял туда блоки. Автоматические клики по полке теперь
идут с приседом — блок ставится, а в полку ничего не попадает. Выключатель — «Не заполнять
полки», по умолчанию включён.

**Новая проверка: полки с содержимым.** По `Ctrl+H` полки вокруг подсвечиваются: зелёные и
залитые — те, в которых лежат предметы, тонкий серый контур — пустые (по желанию их можно
вообще не рисовать). Содержимое полки клиент знает без открытия, поэтому поиск мгновенный и
не требует, как склады, заходить в каждую. Радиус и вид настраиваются во вкладке «Проверка».


**EN**

**Grindstones (and everything that attaches to a block) no longer land anywhere.** The attach
face of such blocks comes from the first suitable look direction, and when you click a
neighbouring block vanilla forces the direction from the clicked block towards the target
first — so the block sticks to whatever you clicked. The solver accounted for that, but when
the wanted attachment is unreachable at all (usually because the supporting block from the
schematic is not built yet) it placed the nearest lookalike. Such a position is now **skipped**
instead of being placed wrong: come back to it once the support exists. Switch: "Skip what
would go in wrong".

**Shelves no longer collect blocks while building.** A shelf takes the item out of your hand on
a right click, so Easy Place next to one dropped blocks onto it. Automated clicks on a shelf now
go through as sneaking — the block is placed and nothing lands on the shelf. Switch: "Do not
fill shelves", on by default.

**New check: shelves with items.** `Ctrl+H` highlights the shelves around you: green and filled
for the ones holding items, a thin grey outline for empty ones (which can be hidden entirely).
The client knows a shelf's contents without opening it, so the search is instant and does not
need you to visit each one the way container search does. Radius and look are in the
"Verification" tab.

## 0.0.11+26.2

**RU**

**Easy Place больше не ставит блок на клетку выше нужной.** Наводка на тонкие голограммы
брала самый дальний подходящий блок по луче, а на пути Litematica по умолчанию
(`easyPlaceFirst`) ожидается ближайший. Из-за этого при взгляде на колонку голограмм первым
ставился дальний блок, а нужный — только следующим кликом, «под ранее поставленным».
Теперь наводка возвращает ближайший блок там, где Litematica ждёт ближайший, и самый
дальний только там, где она сама ищет дальний.

**Наводка перестала перехватывать блоки, в которые и так можно попасть.** Порог «тонкой»
голограммы по одной оси захватывал точило (4 px по одной оси), заборы, двери, люки и стенки —
их наводка подменяла своей. Теперь условие точное: подмена только там, где луч проходит
через клетку блока, но мимо его настоящего контура, то есть попасть в голограмму мышью
действительно нельзя. Точило, забор и всё остальное, во что можно прицелиться, наводится как
раньше — этим занимается сама Litematica.


**EN**

**Easy Place no longer places a block one cell too high.** The thin-ghost targeting returned
the furthest matching block along the ray, while Litematica's default path (`easyPlaceFirst`)
expects the nearest one. Looking at a column of ghosts therefore placed the far block first
and the wanted one only on the next click, "below the one just placed". Targeting now returns
the nearest block where Litematica wants the nearest, and the furthest only where it looks for
the furthest itself.

**Targeting no longer hijacks blocks you can already aim at.** The "thin ghost" test tripped on
a single narrow axis, which caught grindstones (4 px on one axis), fences, doors, trapdoors and
walls — their targeting was replaced by ours. The condition is now exact: we only substitute
where the ray passes through the block's cell but misses its real outline, i.e. where the ghost
genuinely cannot be hit with the crosshair. Grindstones, fences and everything else you can aim
at are targeted as before, by Litematica itself.

## 0.0.10+26.2

**RU**

**Привязка клавиш показывает нажатое сразу.** Кнопка привязки, пока идёт захват, рисовала
только надпись «нажми клавиши…» — набранное сочетание появлялось лишь после Escape.
Теперь текст кнопки обновляется на каждой нажатой клавише, а синяя рамка по-прежнему
показывает, что захват продолжается.

**Escape не теряет прежнюю привязку.** Клик по привязке очищает её, чтобы набрать новую;
если после этого нажать Escape ничего не набрав, старое сочетание возвращается. То же при
переключении вкладки и при закрытии экрана.


**EN**

**Key binding shows the pressed keys immediately.** While capturing, the binding button drew
only the "press keys…" placeholder — the combination appeared only after Escape. The button
text now updates on every key press, while the blue border still shows that capture is
still running.

**Escape no longer loses the previous binding.** Clicking a binding clears it so a new one
can be typed; pressing Escape without typing anything now restores the old combination. The
same applies when switching tabs and when closing the screen.

## 0.0.9+26.2

**RU**

**Плитка настроек сама расширяется под вкладки.** Раньше ширина была жёстко 360 px, и
семь вкладок Material HUD в неё не влезали — «Ghost Alpha» и «Easy Place» обрезались
многоточием. Теперь панель берёт столько ширины, сколько нужно полосе вкладок с
нормальными отступами (для семи вкладок это около 407 px), но растёт не дальше половины
экрана — и никогда не уже прежних 360 px. Подписи выводятся целиком; обрезка с
многоточием и подсказкой осталась только там, где расширяться уже некуда.


**EN**

**The settings tile now widens itself to fit the tabs.** The width used to be a hard
360 px, which Material HUD's seven tabs did not fit — "Ghost Alpha" and "Easy Place" were
ellipsised. The panel now takes as much width as the tab strip needs at normal padding
(about 407 px for seven tabs), but never grows past half the screen — and never below the
old 360 px. Labels are shown in full; ellipsis plus tooltip is left only where there is no
room left to grow into.

## 0.0.8+26.2

**RU**

**Вкладки настроек больше никогда не переносятся на вторую строку.** Ряд
вкладок — всегда одна горизонтальная полоса: сначала сжимаются отступы, потом
подписи укорачиваются с многоточием (полная подпись — в подсказке при
наведении), и лишь если вкладок станет совсем много, полоса начинает
прокручиваться колесом и стрелками «‹ ›» по краям. Отметка об изменённых
настройках переехала в правый верхний угол вкладки, чтобы не толкать соседнюю.

**Примечания разделов больше не вылезают за плитку.** Текст-пояснение под
заголовком раздела убран из тела списка: у заголовка появился знак «?», а само
пояснение раскрывается подсказкой при наведении — целиком, без обрезки.

**Убран «Format Error» в подписях и подсказках.** malilib прогоняет любой
перевод через `String.format`, поэтому одиночный `%` в тексте превращал строку
в `Format Error:...`. Теперь все переводы без подстановок читаются напрямую,
так что проценты в тексте выводятся как проценты.

**Исправлено в подтверждении сброса.** Плитка «Сбросить всё?» делилась пополам, а
слова рисовались слева направо, поэтому нажатие на «Нет» стирало все настройки.
Теперь координаты слов считает та же пара методов, которой пользуется отрисовка, и
полоса подтверждения при нехватке места растёт влево, а не обрезает слово.

**Кнопки аддонов в шапке больше не исчезают** в узком окне: когда места нет даже под
одну кнопку, остаётся «⋯» со списком, а не пустое место — заодно перестала
пересчитываться вёрстка каждый кадр. Кнопка «Назад» больше не сжимается в ноль.

**Перехват клавиш** больше не срывается после первой нажатой клавиши: строки списка
пересобираются при любом изменении, и мод теперь заново находит нужную строку по самой
настройке. **Пробел в поле поиска** больше не переключает выделенную настройку.
**Поиск** не затирает запомненную прокрутку вкладки.

**Easy Place: ставим ровно то состояние, которое просит схема.** Перед каждым
автоматическим кликом мод перебирает варианты клика — какую грань нажать, куда
именно попасть, под каким углом смотреть и с приседом или без — и выбирает тот,
у которого расчётное состояние блока совпадает со схемой. Проверка идёт через
саму ванильную `getStateForPlacement`, поэтому работает для любого блока:
поворот, ось, половина, петли двери, крепление колокола, сторона лестницы,
16 положений табличек и черепов. Присед теперь сообщается серверу настоящим
пакетом ввода, поэтому блок встаёт и вплотную к сундукам, печкам и дверям, а не
открывает их.

**Easy Place: состояние, которого не добиться установкой.** Если блок стоит, но
состояние не то, мод берёт из инвентаря нужный предмет и применяет его один раз
за клик — и только в сторону схемы: огниво зажигает свечи, торты со свечой и
костры, лопата тушит костёр, пустая рука гасит свечу, ведро заливает и осушает
блок, двери и люки открываются пустой рукой. По отдельным выключателям — наполнение
блоков (светокамень, око Эндера, вода в котёл) и перебор кликами (повторитель,
компаратор, датчик света, нотный блок).

**Easy Place: тонкие блоки в воздухе.** Свечи, цепи, подвесные таблички и
прочие блоки с крохотным контуром ловятся по всему кубу, поэтому их можно
ставить, глядя в пустоту, а не пытаясь попасть в двухпиксельную голограмму.
Подвесные таблички Litematica раньше отправляла клик под них и отказывалась
ставить, если снизу воздух — теперь клик считает мод.

**Ещё в счётные блоки добавлен снег** — слои снега получают ровно столько
кликов, сколько в схеме.


**EN**

**Settings tabs never wrap to a second row again.** The tab row is always one
horizontal strip: padding shrinks first, then labels are ellipsised (the full
label shows as a hover tooltip), and only with very many tabs does the strip
start scrolling by wheel and by the "‹ ›" chevrons at its edges. The
changed-settings dot moved to the tab's top right corner so it cannot push the
next tab out.

**Section notes no longer spill out of the tile.** The explanation line under a
section title is gone from the list body: the title now carries a "?" and the
note opens as a hover tooltip, in full, without truncation.

**No more "Format Error" in labels and tooltips.** malilib runs every
translation through `String.format`, so a single `%` in the text turned the
string into `Format Error:...`. Translations without arguments are now read
directly, so percent signs render as percent signs.

**Reset confirmation fixed.** The "Reset all?" tile was split down the middle while the
words were drawn left to right, so clicking "No" wiped every setting. The word positions
now come from the same pair of methods the painter uses, and the confirmation strip grows
leftwards when it does not fit instead of clipping a word.

**Add-on buttons in the header no longer vanish** in a narrow window: when there is no
room for even one button a "⋯" list stays behind instead of empty space — and the layout
stopped being recomputed every frame. The "Back" button no longer shrinks to zero.

**Keybind capture** no longer breaks after the first key: the row list is rebuilt on every
change and the mod now re-finds the row by its setting. **Space in the search box** no
longer toggles the focused setting. **Searching** no longer overwrites the tab's
remembered scroll offset.

**Easy Place: the state the schematic asks for.** Before every automated click
the mod searches the click candidates — which face to press, where exactly to
hit it, which look angle, sneaking or not — and takes the one whose computed
block state matches the schematic. The check runs vanilla's own
`getStateForPlacement`, so it works for any block: facing, axis, half, door
hinge, bell attachment, stair side, the 16 rotations of signs and skulls.
Sneaking is now reported to the server with a real input packet, so blocks go up
flush against chests, furnaces and doors instead of opening them.

**Easy Place: states that placing cannot reach.** When the block is there but
the state is wrong, the mod takes the right item from the inventory and uses it
once per click, and only towards the schematic: flint and steel lights candles,
candle cakes and campfires, a shovel puts a campfire out, a bare hand snuffs a
candle, a bucket waterlogs and drains, doors and trapdoors open with a bare
click. Separate switches cover filling blocks (glowstone, eye of ender, water
into a cauldron) and cycling by clicks (repeater, comparator, daylight sensor,
note block).

**Easy Place: thin blocks in the air.** Candles, chains, hanging signs and other
tiny-outline blocks are targeted by their whole cube, so they can be placed
while you point at empty space instead of trying to hit a two-pixel ghost.
Litematica used to send the click below a hanging sign and refuse when there was
air down there — the mod computes that click itself now.

**Snow layers joined the counted blocks** — they get exactly as many clicks as
the schematic asks for.

## 0.0.7+26.2

**RU**

**Два новых модуля прямо в моде, каждый своей вкладкой с иконкой.**

- **Ghost Alpha** — ползунок прозрачности голограмм Litematica (0 % —
  обычные плотные, 100 % — не видно совсем, схема при этом работает) и две
  тонкие настройки полупрозрачного рендера. Значение живёт в настройках самой
  Litematica, поэтому применяется в том же кадре.
- **Easy Place** — исправления Easy Place для блоков, которые ставятся счётом
  кликов или по направлению взгляда: свечи, морские огурцы, яйца черепахи,
  лепестки и подстилка больше не ставятся сверх схемы; лепестки, подстилка,
  точило (включая потолочное) и светящийся лишайник получают сторону и грань из
  схемы — игрок доворачивается ровно на один клик установки.

Оба модуля выключаются сами, если рядом стоит отдельный мод с той же
функциональностью (Ghost Alpha или Easy Place Fix): тогда его настройки
приходят своей вкладкой через API аддонов, и ничего не дублируется.

**Исправлено: подсчёт останавливался вместе с панелью.** Пока панель была
скрыта (Y), мод не считал установленные и недостающие блоки вообще — данные
собирались заново только при её показе. Теперь подсчёт идёт всегда, а прежнее
поведение осталось переключателем «Считать при скрытой панели» (вкладка
«Панель», раздел «Производительность»). Проверка блоков (Ctrl + B) от панели не
зависела и раньше.

**Вкладки аддонов в настройках.** `LayerListApi` вырос до версии 3:
`registerConfigTab` и нейтральные типы `AddonTab` / `AddonSection` /
`AddonToggle` / `AddonSlider` позволяют другому моду описать свои настройки, а
Material HUD рисует их отдельной вкладкой со своей иконкой — с гейтами,
дочерними строками, сбросом вкладки и сохранением. Прежняя кнопка-аддон
(`registerConfigButton`) работает как раньше.

**Кнопка на экранах Litematica больше не налезает на строку поиска.** Ряд
кнопок аддонов считает реальные ширины соседей, а когда полные подписи не
помещаются после вкладок Litematica, кнопки становятся квадратными иконками с
подсказкой и остаются в строке вкладок — раньше они уходили во второй ряд
поверх поля поиска настроек Litematica.


**EN**

**Two new modules inside the mod, each as its own tab with an icon.**

- **Ghost Alpha** — a slider for how see-through Litematica's ghost blocks are
  (0 % solid as usual, 100 % invisible while the schematic keeps working) plus
  two fine settings of the translucent rendering. The value lives in
  Litematica's own settings, so it applies in the same frame.
- **Easy Place** — fixes for blocks placed by counting clicks or by the
  direction you look: candles, sea pickles, turtle eggs, pink petals and leaf
  litter no longer overshoot the schematic; petals, litter, grindstones
  (ceiling included) and glow lichen get the facing and the face the schematic
  asks for, with the player turned for that single placement click.

Either module stands down automatically when the matching standalone mod
(Ghost Alpha or Easy Place Fix) is installed: its settings then arrive as its
own tab through the add-on API and nothing is duplicated.

**Fixed: counting stopped together with the panel.** While the panel was hidden
(Y) the mod did not count placed and missing blocks at all — the numbers were
rebuilt only when it was shown again. Counting now always runs, and the old
behaviour stayed as the "Count with the panel hidden" toggle (the HUD tab,
performance section). Block verification (Ctrl + B) never depended on the panel.

**Add-on tabs in the settings.** `LayerListApi` is now version 3:
`registerConfigTab` plus the neutral `AddonTab` / `AddonSection` /
`AddonToggle` / `AddonSlider` types let another mod describe its settings, and
Material HUD renders them as a separate tab with its own icon — gates, child
rows, per-tab reset and saving included. The old add-on button
(`registerConfigButton`) is unchanged.

**The button on Litematica's screens no longer covers the search bar.** The
add-on button row now measures the real width of its neighbours, and when the
full labels do not fit after Litematica's tabs the buttons turn into icon
squares with a hover label and stay in the tab row — before they dropped to a
second row on top of Litematica's settings search field.

## 0.0.6+26.2


**EN**

**New: block verification.** Press Ctrl + B — the mod checks the area it counts and outlines every problem in the world: red for a missing block, orange for a wrong block, yellow for a wrong state, blue for an extra block. It is live — a marker appears or disappears the moment you place or break something, and an area coming back into render distance is re-checked on its own. The check follows exactly what the panel counts: your current layer selection, or the zone when Litematica Zone Focus owns the counting area — and it runs alongside Zone Focus's own verification without conflicts. A new "Verification" settings tab holds the master switch, the four categories, see-through, box fill, draw range and the box limit.

**Fixed: chests marked for items they do not contain.** Container contents are remembered per position, and several different paths could write the wrong contents — or write them where no reopening could ever correct them:

- a container screen that was NOT the chest you clicked (your own inventory, a crafting table, a furnace, a mount's inventory, a chest minecart or boat) could be recorded as that chest's contents;
- a right-click that never opened anything (sneak-placing a block against a chest, a blocked or protected chest) still armed the recording for 15 seconds — the window is now 3 seconds and such clicks no longer arm it at all;
- two containers opened in quick succession could swap their contents;
- when a double chest was taken apart and rebuilt, the snapshot stranded on the other half stayed visible forever, because every later opening wrote to a different key;
- an item-frame label kept a chest glowing for an item that had run out;
- blocks that only look like containers to the game (crafter, chiseled bookshelf, jukebox, decorated pot, campfire, lectern) could carry a mark that nothing could ever clear.

A mark is now shown only if opening that very container would overwrite it, so anything wrong heals itself the next time you open the chest. Leftover records from the old versions are cleaned up automatically.

Known limitation: on servers where a plugin replaces a chest's screen with its own (shops, crates, protection menus), that screen is indistinguishable from a real chest for the client and its items may still be recorded. Deleting `config/layerlist_storage.json` clears such records.

**RU**

**Новое: проверка блоков.** Нажмите Ctrl + B — мод проверяет считаемую область и обводит в мире каждую проблему: красным пропущенный блок, оранжевым не тот блок, жёлтым не то состояние, голубым лишний. Проверка живая: рамка появляется и гаснет в момент установки или поломки, а область, вернувшаяся в прорисовку, перепроверяется сама. Проверяется ровно то, что считает панель: текущий выбор слоёв, а при активной зоне Litematica Zone Focus — зона; с собственной проверкой Zone Focus работает бок о бок без конфликтов. Новая вкладка настроек «Проверка»: главный выключатель, четыре категории, сквозь стены, заливка рамок, дальность и предел рамок.

**Изменено: сообщения в чате раскрашены по смыслу.** Каждая строка мода начинается с метки `»` фирменного синего, а слова внутри несут цвет того, о чём говорят: в сводке проверки *пропущено* красное, *не тот блок* оранжевое, *не то состояние* жёлтое, *лишние* голубые — категория с нулём остаётся серой; поиск по хранилищам перечисляет названия блоков ровно теми цветами, которыми они помечены в мире и в панели. Включение — зелёное, выключение — серое, всё, что требует внимания, — янтарное. Сообщения больше не сливаются друг с другом и с чатом сервера.

**Исправлено: сундуки подсвечивались предметами, которых в них нет.** Содержимое хранилищ запоминается по координате, и сразу несколько путей могли записать не то содержимое — или записать его туда, где никакое повторное открытие его уже не исправит:

- содержимым сундука мог записаться экран, который к нему не относится (свой инвентарь, верстак, печка, инвентарь ездового животного, сундук-вагонетка или лодка с сундуком);
- клик, который ничего не открыл (поставил блок крадучись у сундука, сундук перекрыт или приватный), всё равно взводил запись на 15 секунд — теперь окно 3 секунды, а такие клики его вообще не взводят;
- два контейнера, открытых подряд, могли поменяться содержимым;
- когда двойной сундук разбирали и собирали заново, снимок, застрявший на второй половине, оставался видимым навсегда — все последующие открытия писались уже по другому ключу;
- рамка-этикетка держала подсветку сундука по закончившемуся предмету;
- блоки, которые только для кода игры выглядят контейнерами (крафтер, книжная полка с резьбой, музыкальный блок, горшок с узором, костёр, кафедра), могли носить метку, которую нечем было снять.

Теперь метка показывается, только если открытие этого самого контейнера её перезапишет, — поэтому всё неверное лечится следующим открытием сундука. Остаточные записи от старых версий убираются автоматически.

Известное ограничение: на серверах, где плагин подменяет экран сундука своим (магазины, кейсы, меню привата), такой экран для клиента неотличим от настоящего сундука и его предметы всё ещё могут записаться. Такие записи убирает удаление `config/layerlist_storage.json`.

## 0.0.5+26.2


**EN**

- **Build progress is now remembered.** A chunk you have visited keeps its counted progress when it leaves render distance — and survives leaving the world or server. The HUD no longer resets to "not built" on low render distance or after a rejoin.
- New HUD line: "N chunks beyond render distance — using saved progress". The old "counted as not built" line now only covers chunks that were never visited.
- Saved progress lives in `config/layerlist/verify-cache/`, one file per world and dimension, refreshed the moment a chunk is loaded again. It is tied to the exact placement (position, rotation, mirror) and layer selection, so a moved schematic never picks up stale numbers.
- **Fixed: closing the game straight from a world no longer loses the last seconds of saved progress** — the cache is now also written on game shutdown, not only on leaving to the menu.
- **Fixed: rebinding hotkeys now takes effect immediately.** The settings screen did not refresh malilib's key-to-hotkey map after a bind changed, so a newly assigned key stayed dead until restart — only combinations that still contained one of the old keys would fire.
- **New: mouse buttons can be bound.** While capturing a bind, press any mouse button except the left one — right, middle and side buttons are recorded just like keyboard keys.
- **Fixed: the "No schematic loaded" warning no longer lies.** The settings header used to check only the placement selected in Litematica; now any enabled placement counts — matching what the mod actually counts.
- Fixed: typing in the mod switcher's dropdown filter now works on the "Move HUD" screen.
- Fixed: tooltips of button rows no longer show an empty "Default:" line.
- First release built for all three game versions: 26.2, 26.1–26.1.2 and 1.21.11.

**RU**

- **Прогресс стройки теперь запоминается.** Чанк, в котором вы побывали, сохраняет посчитанный прогресс после выхода из прорисовки — и переживает выход из мира или с сервера. Панель больше не сбрасывается в «непостроено» при низкой прорисовке и после перезахода.
- Новая строка панели: «N чанков за пределами прорисовки — по сохранённому прогрессу». Прежняя «считаются непостроенными» осталась только для чанков, где вы ни разу не были.
- Сохранённый прогресс лежит в `config/layerlist/verify-cache/` — файл на мир и измерение, обновляется в момент повторной прогрузки чанка. Привязан к точному размещению схемы (позиция, поворот, зеркало) и выбору слоёв, поэтому сдвинутая схема никогда не подхватит устаревшие цифры.
- **Исправлено: закрытие игры прямо из мира больше не теряет последние секунды сохранённого прогресса** — кеш пишется и при выключении игры, а не только при выходе в меню.
- **Исправлено: переназначение клавиш действует сразу.** Экран настроек не обновлял карту «клавиша → хоткей» в malilib после смены бинда, поэтому новая клавиша молчала до перезапуска — срабатывали только комбинации, где оставалась одна из старых клавиш.
- **Новое: в бинд можно записать кнопки мыши.** Во время захвата нажмите любую кнопку мыши, кроме левой — правая, средняя и боковые записываются так же, как клавиши клавиатуры.
- **Исправлено: предупреждение «Схема не загружена» больше не врёт.** Шапка настроек проверяла только выбранное в Litematica размещение; теперь считается любое включённое — ровно то, что мод и считает.
- Исправлено: набор в фильтре переключателя модов работает и на экране «Передвинуть панель».
- Исправлено: в подсказках строк-кнопок больше нет пустой строки «По умолчанию:».
- Первый релиз сразу для трёх версий игры: 26.2, 26.1–26.1.2 и 1.21.11.

## 0.0.3+26.2


**EN**

- **Counting no longer depends on render distance.** Blocks are read from the schematic itself, so chunks beyond render distance are counted too — as not built. The HUD line changed from "+N chunks not loaded" to "N chunks beyond render distance, counted as not built".
- **New: "Mark colors from blocks"** (off). When on, every mark takes its color from the block itself; when off, marks are painted with the mod's bright palette. Switching it recolors the current marks on the spot.

**RU**

- **Подсчёт больше не зависит от дальности прорисовки.** Блоки читаются из самой схемы, поэтому чанки за пределами прорисовки тоже посчитаны — как непостроенные. Строка панели сменилась с «+N чанков не прогружено» на «N чанков за пределами прорисовки — считаются непостроенными».
- **Новое: «Цвета меток из блоков»** (выкл). Если включить, каждая метка берёт цвет самого блока; если выключить — метки красятся яркой палитрой мода. Переключение перекрашивает текущие метки на месте.

## 0.0.2+26.2

Everything that changed since the first public build (0.0.1).


**EN**

- **A new settings screen**, shared with Litematica Zone Focus: four tabs — HUD, Search, Highlight, Hotkeys — split into sections.
- **Every value is visible at a glance**: On/Off next to the toggle, the number in its own field, the key combination written out in full.
- **Search through all the settings** with `Ctrl` + `F`.
- **Reset** a single option, a whole tab or everything, with the confirmation right where you clicked.
- **The screen warns you when a setting will get in your way**: one row per page while the search follows the page, a refresh slower than five seconds, the smallest search radius, marks with neither stripes nor labels.
- **A status line** says when the mod cannot count anything right now, or when another mod has taken over the counting area.
- **MaLiLib's mod switcher works on the mod's screens too**, so you can jump to another add-on's settings from the header.
- **The mod icon** marks the settings header and the Material HUD button in Litematica's settings.
- **The Material HUD button now sits in Litematica's own row of tabs** and no longer disappears on the Render Layers tab. Its place does not depend on which add-on loaded first.
- **New: "Only empty spots"** (on). A block is counted only where the spot is empty, so a wrong block standing in the way no longer inflates the numbers. Turn it off for the old count.
- **New: "Chat messages"** (on). One switch silences everything the mod writes to chat.
- **The panel position screen was rebuilt**: drag the panel with the mouse, it snaps to the screen edges and centre, `Alt` + `Y` opens it, and Done / Back to Litematica's spot / Cancel finish the job. Chips show where the panel stands, how big it is and its scale.
- Cancel there brings back the position you came in with; only "Back to Litematica's spot" forgets your own coordinates.
- Turning **"Custom position"** off no longer forgets where you had put the panel.
- **Key bindings are colour-coded** by what they do, and every one of them has a reset button.
- **"Show shortfall" is off by default** now, so the red `need N` is yours to turn on. Your other settings are left untouched.
- **"See through blocks" is gone** — marks are always visible through walls. The mark sits inside the block, so with the option off a chest hid its own mark.
- **"Panel position" moved** out of the settings header into the HUD tab, section Placement.
- **Add-on buttons moved to the settings header**, each in its own colour; more than three fold into a "…" menu.
- **Fixed:** marks took the wrong colour from the block — sculk came out yellow, and leaves, grass and other tinted blocks came out grey.
- **Fixed:** two blocks of a similar colour could get marks from opposite ends of the spectrum, which is how a moss block ended up pink. Close colours now differ slightly, or by brightness.
- **Fixed:** the panel header no longer touches the first row of the list, and the panel is no longer a pixel short of its own content.

**RU**

- **Новый экран настроек**, общий с Litematica Zone Focus: четыре вкладки — HUD, «Поиск», «Отметки», «Клавиши» — разбитые на секции.
- **Значение каждой настройки видно сразу**: «Вкл/Выкл» рядом с тумблером, число в своём поле, сочетание клавиш прописано целиком.
- **Поиск по всем настройкам** по `Ctrl` + `F`.
- **Сброс** одной настройки, вкладки или всего сразу, с подтверждением там же, где нажал.
- **Экран предупреждает, когда настройка начнёт мешать**: одна строка на странице при поиске за страницей, пересчёт реже пяти секунд, минимальный радиус поиска, отметки без полосок и подписей.
- **Строка состояния** говорит, когда считать нечего или когда область подсчёта задал другой мод.
- **Переключатель модов MaLiLib работает и на экранах мода** — из шапки можно уйти в настройки соседнего аддона.
- **Иконка мода** стоит в шапке настроек и на кнопке Material HUD в настройках Litematica.
- **Кнопка Material HUD встала в родной ряд вкладок Litematica** и больше не пропадает на вкладке «Отображение слоёв». Её место не зависит от того, какой аддон загрузился первым.
- **Новое: «Только пустые места»** (вкл). Блок считается нужным только там, где место свободно, и не тот блок на пути больше не раздувает числа. Выключить — вернётся прежний подсчёт.
- **Новое: «Сообщения в чат»** (вкл). Один переключатель заглушает всё, что мод пишет в чат.
- **Экран расположения панели переделан**: панель таскается мышью, прилипает к краям и центру экрана, открывается по `Alt` + `Y`, кнопки «Готово» / «Вернуть к углу Litematica» / «Отмена». Чипы показывают, где панель стоит, какого она размера и с каким масштабом.
- «Отмена» там возвращает позицию, с которой вошёл; свои координаты стирает только «Вернуть к углу Litematica».
- Выключение **«Своего расположения»** больше не забывает, куда ты поставил панель.
- **Привязки клавиш раскрашены по смыслу**, и у каждой есть кнопка сброса.
- **«Показывать нехватку» теперь выключено по умолчанию** — красное `нет N` включаешь сам. Остальные твои настройки не трогаются.
- **Настройки «Видно сквозь блоки» больше нет** — отметки всегда видно через стены. Отметка сидит внутри блока, и с выключенной опцией сундук закрывал сам себя.
- **«Расположение панели» переехало** из шапки настроек во вкладку HUD, секцию «Размещение».
- **Кнопки аддонов переехали в шапку настроек** и помечены своим цветом; больше трёх сворачиваются в меню «…».
- **Исправлено:** отметки брали с блока не тот цвет — скалк выходил жёлтым, а листья, трава и другие тонированные блоки серыми.
- **Исправлено:** два блока похожего цвета могли получить отметки с разных концов спектра — так блок мха становился розовым. Теперь близкие цвета отличаются чуть-чуть или яркостью.
- **Исправлено:** шапка панели больше не слипается с первой строкой списка, а сама панель не обрезает своё содержимое на пиксель.

---

# Архив: внутренние номера сборок до выпуска

## 1.3.3+26.2


**EN**

- The HUD position screen now looks like every other screen of the pair: the same panel with rounded corners, the crumb above it, the title with the live X/Y next to it, status chips, the footer rule and the same Done / Back to Litematica's spot / Cancel buttons with their hover and press animations. Nothing about how the panel is dragged, snapped or saved has changed.
- The chips show at a glance whether the panel sits in its own spot or in Litematica's, how large it is on screen and, when it is not 1.00×, its scale.

**RU**

- Экран расположения HUD теперь выглядит так же, как остальные экраны пары: та же панель со скруглениями, надпись-путь над ней, заголовок с живыми X/Y рядом, чипы состояния, линия над подвалом и те же кнопки «Готово» / «Вернуть к углу Litematica» / «Отмена» с их анимациями наведения и нажатия. Перетаскивание панели, прилипание к краям и сохранение не изменились.
- Чипы сразу показывают, стоит панель на своём месте или в углу Litematica, какого она размера на экране и, если масштаб не 1.00×, какой он.

## 1.3.2+26.2


**EN**

- **New: "Only empty spots" (on by default).** A block counts as needed only where the world is empty. A spot taken by the wrong block or the wrong state no longer inflates the shortfall — you cannot place anything there anyway until it is cleared, and finding those spots is what verification is for. Turn it off to get the old count back, mismatches included.
- **New: "Chat messages" (on by default).** One switch silences everything the mod writes to chat — the panel toggle and the storage search.
- Note on the numbers: the option changes the material list and the progress bar, not Zone Focus's zone percentages. A zone with a wrong block still counts as unfinished there, and Zone Focus's verification highlights exactly which spots.

**RU**

- **Новое: «Только пустые места» (по умолчанию вкл).** Блок считается нужным только там, где в мире пусто. Место, занятое не тем блоком или не тем состоянием, больше не раздувает нехватку: поставить туда всё равно нечего, пока не расчистишь, а искать такие места — работа проверки схемы. Выключить — вернётся прежний подсчёт вместе с несовпадениями.
- **Новое: «Сообщения в чат» (по умолчанию вкл).** Один переключатель заглушает всё, что мод пишет в чат: панель и поиск по хранилищам.
- Про числа: опция меняет список материалов и полосу прогресса, но не проценты зон в Zone Focus. Там зона с не тем блоком по-прежнему недостроена, а проверка Zone Focus подсвечивает, где именно.

## 1.3.1+26.2


**EN**

- **Fixed: 1.3.0 crashed the game when Litematica's config screen opened.** The helper class holding the addon slot order sat in the mixin package, and Mixin refuses to transform a class there that is not a registered mixin. The constants moved back into the mixins themselves. Found by opening the screen in the game — 1.3.0 must not be used.
- **Fixed: the addon buttons no longer cover Litematica's own tabs.** When the native tab row already fills the window, the addon group used to slide left on top of "Hotkeys" and "Render Layers"; now it drops to a second row, right-aligned. On a wide window nothing changes.
- **Fixed: text in every control sat one pixel too high.** Vertical centring assumed a 9 px line where the font paints 8, so the gap above and below was uneven — most visible in the keybind boxes.
- **Fixed: the reset icon was a broken glyph.** `⟲` is not in the game font; it is now the same 7×7 pixel mask the mod has always used, centred in its box.
- **Keybind rows are colour-coded**, the same way as in Zone Focus: the panel toggle green, paging and screens blue, storage search the colour of its marks, clearing the marks red — as a square next to the name and as the border and a faint fill of the key box itself.
- Tab labels get less padding instead of wrapping to a second row when they do not fit.

**RU**

- **Исправлено: 1.3.0 крашила игру при открытии настроек Litematica.** Класс с порядком слотов кнопок лежал в mixin-пакете, а Mixin отказывается трансформировать класс оттуда, если он не зарегистрирован как миксин. Константы вернулись в сами миксины. Поймано открытием экрана в игре — 1.3.0 ставить нельзя.
- **Исправлено: кнопки аддонов больше не наезжают на вкладки Litematica.** Когда нативный ряд уже занял всё окно, группа аддонов сдвигалась влево поверх «Hotkeys» и «Render Layers»; теперь она уходит на вторую строку, прижатая вправо. На широком окне ничего не меняется.
- **Исправлено: текст во всех контролах стоял на пиксель выше центра.** Центрирование считало строку в 9 px, а шрифт рисует 8, поэтому зазор сверху и снизу был разным — заметнее всего в полях привязок.
- **Исправлено: иконка сброса была битым глифом.** `⟲` в шрифте игры нет; теперь это та же маска 7×7, что всегда была в моде, по центру своей рамки.
- **Строки привязок раскрашены по смыслу**, как в Zone Focus: панель — зелёный, листание и экраны — синий, поиск по хранилищам — цвет его отметок, сброс отметок — красный. Цвет идёт и квадратом у названия, и обводкой с лёгкой заливкой самого поля клавиши.
- Подписи вкладок сжимают отступ вместо переноса на вторую строку, когда не влезают.

## 1.3.0+26.2


**EN**

- **Fixed: the Material HUD button no longer disappears on Litematica's "Render Layers" screen.** That tab is a separate screen class (`GuiRenderLayer`) that draws Litematica's tab row itself, and the addon button was only added to `GuiConfigs` — so it vanished the moment you switched to that tab. The button is now added to both screens, in the same slot, with the same width.
- **"Show shortfall" is off by default.** The red "need N" next to a count is opt-in now.
- **"See through blocks" is gone as a setting — marks are always drawn on top.** The dot sits inside the block, so with the option off the chest hid its own mark; nothing was gained by being able to turn it off.
- **Every keybind row has a reset icon.** It used to appear only after you changed the binding; since clicking a keybind clears it first, there was no visible way back to the default.
- **The panel position screen got the Zone Focus look**: rounded buttons with the same fill, border and hover animation, a card for the hint line, a green frame around the panel being dragged, the mod name above the hint as a tracked crumb. Both mods now share one look on every screen.
- **The description no longer starts with "A Litematica add-on by Aell_"** — the author is already in the "Author" field. All 14 languages.
- Icon replaced with the 512×512 one.
- Settings changed by previous versions are migrated: this release forces "Show shortfall" back to its new default once, then leaves your choices alone.

**RU**

- **Исправлено: кнопка Material HUD больше не пропадает на экране «Отображение слоёв» Litematica.** Эта вкладка — отдельный класс экрана (`GuiRenderLayer`), который сам рисует ряд вкладок Litematica, а кнопка аддона добавлялась только в `GuiConfigs` — поэтому исчезала, стоило переключиться на эту вкладку. Теперь кнопка добавляется на оба экрана, в тот же слот и той же ширины.
- **«Показывать нехватку» выключено по умолчанию.** Красное «нет N» рядом с количеством теперь включается вручную.
- **Настройки «Видно сквозь блоки» больше нет — отметки всегда рисуются поверх.** Точка сидит внутри блока, так что с выключенной опцией сундук закрывал собственную отметку; возможность её выключить не давала ничего.
- **У каждой строки с клавишей есть иконка сброса.** Раньше она появлялась только после изменения бинда, а клик по бинду его сначала стирает — вернуться к дефолту было нечем.
- **Экран расположения панели получил вид Zone Focus**: скруглённые кнопки с той же заливкой, обводкой и анимацией под курсором, плашка-карточка для строки подсказки, зелёная рамка вокруг перетаскиваемой панели, имя мода над подсказкой разрежённым шрифтом. Теперь у обоих модов один вид на всех экранах.
- **Описание больше не начинается с «Аддон к Litematica от Aell_»** — автор и так указан в поле «Автор». Все 14 языков.
- Иконка заменена на 512×512.
- Настройки, сохранённые прошлыми версиями, переносятся: этот выпуск один раз возвращает «Показывать нехватку» к новому значению по умолчанию и больше в твой выбор не лезет.

## 1.2.0+26.2


**EN**

- **Fixed: the panel header no longer touches the first row of the list.** The list start was a fixed offset while the header height varies with the progress line and the panel scale; now it is derived from the measured header plus a guaranteed gap. The panel height was one pixel short of its own content too.
- **Fixed: storage colours are actually taken from the item icon now.** Red and blue channels were swapped when reading the texture, which is why sculk came out yellow. Runtime tints (leaves, grass) are applied before averaging, so tinted blocks no longer read as grey.
- **Fixed: a colour is no longer thrown across the wheel to keep marks apart.** Two items with close hues used to push one of them up to 194° away — that is how a moss block became pink. Now the nudge is at most 18°, and if that is not enough the marks differ by brightness instead; the fixed palette is the last resort, not the second one.
- **"Panel position" moved out of the settings header into the HUD tab**, section "Placement", where it belongs. `Alt+Y` and the drag screen itself are unchanged; the header slot stays reserved for buttons other mods register.
- **The settings screen got the look of Zone Focus's zones screen**: rounded rows and controls, an animated toggle knob, a status plate with a coloured dot, a fading hover.
- **The Material HUD button now sits in Litematica's own tab row**, sized and styled like the native tabs, right after them.
- **The mod switcher from MaLiLib is available on our screens too** — settings and the panel position screen. Material HUD itself now appears in that list.
- The middle footer button reads "Reset tab (N)" in full.

**RU**

- **Исправлено: шапка панели больше не слипается с первой строкой списка.** Начало списка было фиксированным смещением, а высота шапки переменная — она зависит от строки прогресса и масштаба панели; теперь оно считается от измеренной шапки плюс гарантированный зазор. Заодно высота панели была на пиксель меньше её собственного содержимого.
- **Исправлено: цвет отметки действительно берётся с иконки предмета.** При чтении текстуры красный и синий каналы менялись местами — именно поэтому скалк выходил жёлтым. Рантайм-тонировка (листья, трава) теперь применяется до усреднения, так что тонируемые блоки больше не читаются как серые.
- **Исправлено: цвет больше не отбрасывает на другую сторону круга ради различимости.** Два предмета с близкими оттенками могли развести на 194° — так блок мха и становился розовым. Теперь сдвиг не больше 18°, а если этого мало, отметки разводятся по яркости; фиксированная палитра — последнее средство, а не второе.
- **«Расположение панели» переехало из шапки настроек в вкладку HUD**, секция «Размещение», где ему и место. `Alt+Y` и сам экран перетаскивания не изменились; слот в шапке остался за кнопками, которые регистрируют другие моды.
- **Экран настроек получил вид экрана зон Zone Focus**: скруглённые строки и элементы, анимированный бегунок тумблера, плашка состояния с цветной точкой, плавная подсветка под курсором.
- **Кнопка Material HUD встала в родной ряд вкладок Litematica**, того же размера и стиля, сразу за нативными вкладками.
- **Переключатель модов из MaLiLib теперь есть и на наших экранах** — в настройках и на экране расположения панели. Сам Material HUD тоже появился в этом списке.
- Средняя кнопка футера читается полностью — «Сбросить вкладку (N)».

## 1.1.0+26.2


**EN**

- **A brand new settings screen, shared with Litematica Zone Focus.** Same grid, same palette, same behaviour in both mods.
- **The value of every option is always visible**: On/Off next to the toggle, the number in its own field, the key combo spelled out in full.
- **Search across all tabs** on Ctrl+F, with the matching fragment highlighted and the tab name in front of each row.
- **Sections inside a tab** with headers.
- **Warnings instead of silence**: one block per page with the search following it, a refresh slower than five seconds, the smallest search radius, marks with neither stripes nor labels — each says what will go wrong without blocking anything.
- **A status line** under the tabs when there is something to say, including when another mod has taken over the counting area.
- **Reset on three levels**: one row, the whole tab, or the whole config, with confirmation in place instead of a popup.
- **The HUD position screen was rebuilt** to the same palette and now has Done, Cancel and "Back to the Litematica corner". Cancel restores the position you came in with; only "Back to the corner" erases the saved coordinates.
- Turning "Custom position" off no longer loses the coordinates — turn it back on and the panel returns where you put it.
- Add-on buttons moved from the footer to the header and are marked in their own colour; more than three collapse into a "⋯" menu.
- The button in Litematica's own settings now keeps a fixed slot order regardless of which mod loaded first.

**RU**

- **Совершенно новый экран настроек, общий с Litematica Zone Focus.** Одна сетка, одна палитра, одно поведение в обоих модах.
- **Значение каждой настройки видно всегда**: «Вкл/Выкл» рядом с тумблером, число в своём поле, сочетание клавиш прописано целиком.
- **Поиск сразу по всем вкладкам** по Ctrl+F, совпавший кусок подсвечен, перед строкой — имя вкладки.
- **Секции внутри вкладки** с заголовками.
- **Предупреждения вместо молчания**: один блок на странице при поиске за страницей, пересчёт реже пяти секунд, минимальный радиус поиска, отметки без полосок и подписей — каждое говорит, что пойдёт не так, и ничего не блокирует.
- **Строка состояния** под вкладками, когда есть что сказать, — в том числе когда область подсчёта задал другой мод.
- **Сброс на трёх уровнях**: строка, вкладка, весь конфиг, с подтверждением на месте вместо всплывающего окна.
- **Экран расположения панели переделан** в ту же палитру: «Готово», «Отмена» и «Вернуть к углу Litematica». «Отмена» возвращает позицию, с которой вошёл; координаты стираются только кнопкой возврата к углу.
- Выключение «Своего расположения» больше не теряет координаты — включил обратно, панель вернулась туда же.
- Кнопки аддонов переехали из футера в шапку и помечены своим цветом; больше трёх сворачиваются в меню «⋯».
- Кнопка в настройках самой Litematica теперь занимает постоянный слот независимо от того, какой мод загрузился первым.

## 1.0.6+26.2


**EN**

- **Storage search colours now come from the item's own icon.** Coal is no longer marked pink: the icon texture is averaged with a bias towards its saturated pixels, then pulled up in saturation and brightness so the marker reads against a dark world. Items whose colours would be indistinguishable get their hue nudged apart; greys (coal, iron, quartz) fall back to the old palette instead, since shifting the hue of a grey changes nothing.
- **A reset button on every settings row.** It appears next to the control only while the value differs from the default, and one click puts that single option back.
- **The settings screen got noticeably tighter**: a narrow column pinned to the left edge, shorter tabs and rows, smaller footer buttons.
- **Colour marks in the settings** where colour means something: shortfall red, progress bar green, storage markers and in-chest highlight cyan, world boxes and labels blue.
- **A Material HUD button in Litematica's own settings screen**, in the tab row on the right. Zone Focus takes the slot next to it when both mods are installed.
- The HUD position screen now says "Back" instead of "Done" — it does exactly that.

**RU**

- **Цвета поиска по сундукам берутся из иконки самого предмета.** Уголь больше не помечается розовым: текстура иконки усредняется с перевесом в сторону насыщенных пикселей, затем цвет дотягивается по насыщенности и яркости, чтобы отметка читалась в тёмном мире. Предметам, чьи цвета глазом не различить, оттенок разводится; серым (уголь, железо, кварц) вместо этого достаётся цвет старой палитры — сдвигать оттенок у серого бессмысленно.
- **Кнопка сброса у каждой строки настроек.** Появляется рядом с элементом управления, только когда значение отличается от стандартного, и одним щелчком возвращает именно эту настройку.
- **Экран настроек стал заметно плотнее**: узкая колонка у левого края, ниже вкладки и строки, меньше кнопки в футере.
- **Цветные метки в настройках** там, где цвет что-то значит: нехватка — красная, полоса прогресса — зелёная, отметки на сундуках и подсветка в контейнере — голубые, рамки и подписи в мире — синие.
- **Кнопка Material HUD в настройках самой Litematica**, в строке вкладок справа. Zone Focus занимает соседний слот, если стоят оба мода.
- На экране расположения HUD вместо «Готово» теперь «Назад» — кнопка ровно это и делает.

## 1.0.5+26.2


**EN**

- **The settings screen works like the old one again.** The panel sits at the left edge instead of being centred, tabs and footer buttons line up from the left at the width of their own labels, and option rows show just the name — the description is a tooltip that appears when the pointer is over the row. The tooltip is drawn in the mod's own style, not in the vanilla frame with its purple border.
- **A hotkey for the HUD position screen**: `Alt+Y` by default. Settings stay on `Ctrl+Y` — mod screens are on Alt, settings on Ctrl.
- **Storage markers are elongated diamonds** instead of square dots — the same shape as the green marker on the mod's icon. The diamond faces the player and keeps its on-screen size with distance, and in a stack of several items they no longer touch.

**RU**

- **Экран настроек снова работает как старый.** Панель стоит у левого края, а не по центру, вкладки и кнопки футера идут слева по ширине своих подписей, в строке настройки — только название, а пояснение всплывает подсказкой при наведении на строку. Подсказка рисуется в оформлении мода, а не ванильной плашкой с фиолетовой рамкой.
- **Бинд на экран расположения HUD**: по умолчанию `Alt+Y`. Настройки остались на `Ctrl+Y` — экраны мода на Alt, настройки на Ctrl.
- **Отметки на сундуках стали вытянутыми ромбами** вместо квадратных точек — та же фигура, что зелёный маркер на значке мода. Ромб развёрнут к игроку и держит экранный размер с расстоянием, а в столбике из нескольких предметов ромбы больше не слипаются.

## 1.0.4+26.2


**EN**

- **New mod icon** in the same neutral tone: dark plate, the three cubes and the green marker unchanged.
- **The purple look is gone.** Screens no longer paint their own background: a flat dark dim over the game and neutral dark-grey panels, the same neutral tone Litematica's own screens use — the in-game HUD panel included. The settings screen keeps its tabs, and colour still marks meaning: green for a toggle that is on, blue for the selected tab and progress, red/yellow for shortfall, and the per-item search colours.

**RU**

- **Новая иконка мода** в том же нейтральном тоне: тёмная плашка, три куба и зелёный маркер на месте.
- **Фиолетового оформления больше нет.** Экраны не красят фон в свой цвет: ровное затемнение поверх игры и нейтральные тёмно-серые панели в тон экранам самой Litematica — панель HUD в игре тоже. Разбивка настроек по вкладкам осталась, цвет по-прежнему обозначает смысл: зелёный — включённый тумблер, синий — выбранная вкладка и прогресс, красный/жёлтый — нехватка, плюс цвета предметов в поиске по сундукам.

## 1.0.3+26.2


**EN**

- **Fixed: with the list hidden, storage markers froze.** Counting stopped whenever the HUD was toggled off, so the set of searched items stayed as it was at that moment — you could take exactly what you needed (or nothing at all) and the chest kept its dot until you turned the list back on. Counting now keeps running while a storage search is active, so the markers update with the list hidden too.

**RU**

- **Фикс: при выключенном списке подсветка сундуков замирала.** Пересчёт останавливался вместе со скрытием HUD, поэтому набор искомых предметов оставался таким, каким был на момент выключения: возьми ровно сколько нужно (или не бери вовсе) — точка на сундуке горела до включения списка обратно. Теперь пересчёт продолжается, пока активен поиск по хранилищам, и отметки обновляются даже со скрытым списком.

## 1.0.2+26.2


**EN**

- **The list now reacts to a block the moment it is placed or broken.** Counts used to wait for the next refresh tick (`refreshIntervalTicks`), and the old right-click hook fired *before* the block existed, so a block you had just placed could stay in the list. A mixin on `ClientLevel.setServerVerifiedBlockState` — the same point Litematica uses for its schematic verifier — marks the list dirty on every confirmed block change, yours or anyone's (pistons and water included).

**RU**

- **Список реагирует на блок сразу, как его поставил или сломал.** Раньше цифры ждали очередного тика обновления (`refreshIntervalTicks`), а старый хук правого клика срабатывал *до* появления блока — поэтому только что поставленный блок мог остаться в списке. Миксин на `ClientLevel.setServerVerifiedBlockState` (та же точка, из которой Litematica обновляет свою проверку схемы) помечает список к пересчёту на любое подтверждённое изменение блока: своё, чужое, поршнем, водой.

## 1.0.1+26.2


**EN**

- **Fixed: storage markers went out as soon as you picked up a single block.** "Skip blocks you already carry" dropped an item from the search on any amount in the inventory, so one stone out of the 700 you need turned the chests off. It now compares what you carry against what is still left to place (`available >= missing`), so the markers stay on until you actually have enough. Option renamed to "Skip blocks you have enough of".

**RU**

- **Фикс: отметки на сундуках гасли от одного подобранного блока.** Опция «не искать то, что есть с собой» выкидывала блок из поиска при любом количестве в инвентаре — один камень из нужных 700 выключал подсветку. Теперь сравнивается то, что в инвентаре, с тем, что осталось поставить (`available >= missing`): отметки держатся, пока нужного количества нет. Опция переименована в «не искать то, чего хватает».

## 1.0+26.2


**EN**

- Release version reset to **1.0**.
- Added a mod icon.
- Author is now listed as **Aell_**.
- One universal jar instead of three language builds: the interface follows the game language, and the Mod Menu card (name row + description) is translated into 14 languages — en, ru, uk, be, de, fr, es, pt-BR, it, pl, tr, zh-CN, ja, ko.

**RU**

- Версия релиза сброшена на **1.0**.
- Добавлена иконка мода.
- Автор указан как **Aell_**.
- Вместо трёх языковых сборок — один универсальный jar: интерфейс следует за языком игры, карточка мода в Mod Menu (строка-описание) переведена на 14 языков — en, ru, uk, be, de, fr, es, pt-BR, it, pl, tr, zh-CN, ja, ko.

## 1.3.0+26.2


**EN**

- Added an addon API (`com.aell.layerlist.api`): a `CountScope` can replace "count the current layer" with any set of world boxes, and addons can add their own button to the settings screen. Nothing changes without an addon installed.
- Used by **Litematica Region HUD**, which counts materials for one zone of a placed schematic at a time.

**RU**

- Появился API для аддонов (`com.aell.layerlist.api`): `CountScope` подменяет «считать текущий слой» на любой набор боксов в мире, а аддон может добавить свою кнопку в экран настроек. Без аддонов ничего не меняется.
- Используется аддоном **Litematica Region HUD**, который считает материалы по одной зоне поставленной схемы.

## 1.2.0+26.2

Everything that changed since 1.0.0 (1.1.0 was an internal build and was never published).


**EN**

*New*

- **Move the HUD wherever you want.** Settings now have a **“Move HUD...”** button at the top. It opens a screen with the panel drawn as it looks in game — grab it with the mouse and drop it anywhere. It snaps to the screen edges and to the center so it sits straight. Dragging it also turns the custom position on, and the **“Back to Litematica's spot”** button hands the placement back to Litematica's Info HUD alignment. The position survives resolution and GUI-scale changes: the panel is kept inside the screen.
- **Flip pages with a container open.** New option **“Scroll pages in containers”** (on by default): your scroll keys also work while a chest, barrel or shulker GUI is open, and the panel is shown in those screens so you can see what you are flipping through. No extra keybind — the same keys. Outside containers (chat, anvil name field, other screens) the keys are left alone.

*Fixed*

- **The HUD scale slider.** It had no range, so it stretched over the entire range of a double and printed a screen full of zeros; nudging it blew the scale up to an absurd number. It is now **0.25 … 3.0 with a 0.25 step**, and a broken value saved by the old slider is repaired on load.
- **The page hint shows your real keys.** The footer used to say “PgUp/PgDn” no matter what you had bound. It now reads the actual keybinds (`Ctrl + Up`, `Num5`, `Mouse4`, …), and shows nothing extra if you cleared both.
- **The settings screen title** showed the raw `layerlist` key instead of the mod name.

*Storage markers*

- **Dots sit in the middle of the container and stand still.** In 1.0.0 they floated above the lid and pulsed. Now the dot is inside the chest, static, at the size you set. Several found block types in one container = a tight column around its center. The column moves above the lid only when you stand higher than the container, where the lid itself would hide it. (The dot is inside the block, so it relies on **“See through blocks”**, which is on by default.)
- **Item names can no longer overlap.** Instead of one merged line (“Stone, Dirt”, “Stone +2”), every found block gets **its own line in its own color** — the same color as its dot and as its stripe in the HUD — stacked above the container. More than five blocks in one chest end with a “+N” line. Labels of neighbouring containers (a chest wall) push each other up instead of printing on top of each other, and a lifted stack gets a thin leader line down to its dots so you can tell which chest it belongs to. The layout is derived from block positions, so it is identical every frame. Label range is now 32 blocks (was 48).

Your existing `config/layerlist.json` is picked up as is. New options default to: scrolling in containers **on**, custom HUD position **off**.

**RU**

Всё, что изменилось с 1.0.0 (1.1.0 был промежуточной сборкой и не публиковался).

*Новое*

- **Панель можно поставить куда угодно.** В настройках сверху появилась кнопка **«Расположение HUD...»**. Она открывает экран, где панель нарисована ровно так, как выглядит в игре: берёшь её мышью и кладёшь в любое место. Панель прилипает к краям экрана и к центру, чтобы села ровно. Перетаскивание само включает своё расположение, а кнопка **«Вернуть к углу Litematica»** отдаёт расстановку обратно настройкам Info HUD Litematica. Позиция не теряется при смене разрешения и масштаба интерфейса — панель удерживается в пределах экрана.
- **Листание страниц с открытым сундуком.** Новая опция **«Листать в контейнерах»** (включена): клавиши листания работают и когда открыт сундук, бочка или шалкер, и панель в этих экранах показывается — видно, что листаешь. Отдельный бинд не нужен, те же клавиши. Вне контейнеров (чат, ввод названия в наковальне, прочие экраны) клавиши не перехватываются.

*Починено*

- **Ползунок масштаба HUD.** У настройки не было границ, поэтому ползунок растягивался на весь диапазон double и показывал экран нулей, а любое движение задирало масштаб в астрономическое число. Теперь **0.25 … 3.0 с шагом 0.25**, а испорченное старым ползунком значение в конфиге лечится при загрузке.
- **Подсказка страниц показывает настоящие клавиши.** В футере всегда было написано «PgUp/PgDn», что бы ты ни забиндил. Теперь подсказка читает текущие привязки (`Ctrl + Up`, `Num5`, `Mouse4`, …), а если снять обе — лишнего текста нет.
- **Заголовок экрана настроек** показывал сырой ключ `layerlist` вместо названия мода.

*Отметки на хранилищах*

- **Точки — в центре контейнера и не шевелятся.** В 1.0.0 они висели над крышкой и пульсировали. Теперь точка внутри сундука, статичная, того размера, что выставлен в настройках. Несколько найденных видов блоков в одном контейнере = плотный столбик у его центра. Наверх, над крышкой, столбик уходит только когда стоишь выше контейнера — оттуда центр закрывала бы сама крышка. (Точка внутри блока, поэтому она опирается на опцию **«Видно через блоки»**, включённую по умолчанию.)
- **Названия предметов больше не наслаиваются.** Вместо одной склеенной строки («Stone, Dirt», «Stone +2») у каждого найденного блока **своя строка своим цветом** — тем же, что у его точки и у полоски в HUD, — столбиком над контейнером. Если блоков больше пяти, последняя строка — «+N». Подписи соседних контейнеров (стенка сундуков) расталкиваются по высоте вместо того, чтобы печататься друг на друге, а к поднятой стопке идёт тонкая линия-поводок вниз к её точкам — видно, чей это ярлык. Раскладка считается от координат блоков, поэтому в каждом кадре одинаковая. Радиус подписей — 32 блока (было 48).

Существующий `config/layerlist.json` подхватывается как есть. Новые опции по умолчанию: листание в контейнерах **вкл**, своё расположение HUD **выкл**.

## 1.0.0+26.2


**EN**

Initial release for Minecraft 26.2 (Fabric).

Requires Litematica and MaLiLib (built against 0.28.4 and 0.29.3) plus Fabric API. Client-side only.

Three files — install only one: `-auto` follows your game language, `-en` is always English, `-ru` is always Russian.

**RU**

Первый релиз для Minecraft 26.2 (Fabric).

Нужны Litematica и MaLiLib (собрано под 0.28.4 и 0.29.3) и Fabric API. Только клиентская часть.

Три файла — ставить нужно один: `-auto` следует за языком игры, `-en` всегда английский, `-ru` всегда русский.
