# Litematica Material HUD & Chest Finder — changelog

English release notes as published on [Modrinth](https://modrinth.com/mod/material-hud/changelog), newest first.
The original Russian notes are in [CHANGELOG.ru.md](CHANGELOG.ru.md).

## 1.0.22 — 2026-10-26

Minecraft 26.3, 26.2, 1.21.11

#### New
- **Block check (Ctrl+B) now highlights schematic entities too** — cushions, armor stands, item frames, paintings. A red box means the entity is missing or the frame is empty; a yellow box means it stands wrong (rotation, another cushion colour, another item in the frame). Same layers, area and range as blocks, and the count goes into the check summary.
- **Padlock in the placement editor.** Type the coordinates, press the padlock, and the schematic can no longer be moved by the mouse, the keys or Litematica's tool (it is Litematica's own lock). A locked placement shows a gold padlock and a gold frame in the world. In the Placements tab the right-click menu has *Lock* / *Unlock*. *Cancel* in the editor restores the lock as it was.
- **"Look at the world" in the placement editor**, like in the Selection tab. The editor closes, you walk and fly, and a placement card with live coordinates stays in the top right. **R** puts the cursor on the card (fields, +/−, rotate, mirror, *To me*, *Move in world*, padlock), **Esc** returns to the editor.
- **Placement editor buttons are coloured by meaning:** *Done* green, *Cancel* red dashed, −/+ in their axis colour, rotate and mirror blue, the padlock gold.
- **Rename schematics in the Library.** Right-click a schematic → *Rename…*. Placements in the world, block replacements and disabled blocks from Materials, Zone Focus zones and the thumbnail move to the new name with the file. The message after renaming has *Undo*.
- **Easy Place lights fire.** Fire and soul fire from the schematic are placed with flint and steel (or a fire charge): the mod clicks the block under the cell, as a player would.
- **Redstone, rails and string are visible in holograms.** The "missing block" overlay used to cover them with a solid blue square; now they get only the cell frame and you can see where the line goes. Settings → Ghost Alpha → *Redstone, rails and string without fill* (on by default).

#### Fixed
- fix — **a patterned banner was not placed while a banner of the same colour without a pattern (or with another pattern) was in hand.** Easy Place thought the right banner was already in hand and did not take it from the backpack, so the cell stayed empty. It happened mostly with a full hotbar. Now the banner with exactly the right pattern is taken.
- fix — **Easy Place did not place string.** A string cell was silently skipped in survival and creative.
- fix — **not every row of the Materials tab could be disabled** — Glow Berries, a flower in a pot, dirt under farmland and other rows whose item differs from the block had no *Disable*. Every row can be disabled now.
- fix — **water and lava from the schematic were never counted as built**, so the Water Bucket row never went down.
- fix — **with "Count up to Y" blocks were counted on the current layer only** when Litematica's layer mode was not *All*. Blocks are now counted up to Y, like entities.
- fix — **banners, pots and heads inside a shulker box or bundle did not count as "with you" in the HUD.** Plain blocks did; now both do.
- fix — **Light levels were merged into one row of the Materials table.** Each level has its own row now, as in the HUD.
- fix — **after removing the last placement, "HUD list" in the table still showed the old rows.**
- fix — **typing into a coordinate locked by axis in Litematica silently did nothing.** The field is grey now and the editor says the axis is locked.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib · Litematica · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.21 — 2026-10-24

Minecraft 26.3, 26.2, 1.21.11

#### Fixed
- fix — **Easy Place did not place the invisible Light block.** A Light block has no outline unless the Light item is in your hand, so the aim went straight through the hologram and no click happened at all. Light cells are now found, and Easy Place takes the Light item of **exactly the level the schematic wants** (it used to put level 15 where 7 was needed). No item of that level in the inventory — the cell is skipped instead of getting a wrong level. In creative the right level is picked by itself.
- fix — **all Light levels were merged into one row** of the material list. Each level now has its own row — **Light 15**, **Light 7** and so on — so you see which light to bring.
- fix — **a long line at the bottom of the HUD stretched the panel** across half the screen (for example *"Snapshot from Overworld: <long placement name> — totals frozen, inventory is live"*). Footer lines no longer set the panel width: when they do not fit, they **scroll inside the panel**. A long add-on area label at the right of the title scrolls too.
- fix — **menus and dropdowns grew to fit the longest schematic name** (the schematic picker in the Materials tab, *Source* in the export window). A very long name is now shown with an ellipsis and scrolls in full under the cursor.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib · Litematica · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.20 — 2026-10-23

Minecraft 26.3, 26.2, 1.21.11

#### New
- **Count storage in the HUD.** Settings → HUD → *Panel content* → **Count storage** (off by default). The panel hides what you already have — in your inventory and in the chests, barrels and shulker boxes you have opened on this server in this dimension. If the schematic needs 144 dirt and it is in a chest, the Dirt row is gone; only what is missing entirely stays.
  - **A chest counts only after you open it** at least once — the mod remembers what it saw inside, as of your last look.
  - Rows covered only partly stay, and **"need N"** subtracts the chests too. The footer says how many rows are hidden.
  - **While a storage is open, the list is full**, so **Move everything needed** still takes the hidden items.
- The **Count storage** toggle in the **Materials** tab is now the same setting: it is remembered between launches and works without *Only missing*.

#### Fixed
- fix — the **In chests** column tooltip said shulker box contents in chests are not seen. They have been counted for a while; the text is fixed.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib · Litematica · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.19 — 2026-10-22

Minecraft 26.3, 26.2, 1.21.11

#### New
- **Look: Aell UI, Vanilla or Vanilla+.** Settings → HUD → *Interface style* → *Look*. **Aell UI** is the default — nothing changes for you.
  - **Vanilla** switches every window of the mod to Minecraft's own look: settings, schematic library, materials table, export, dialogs, placement editor, the HUD panel, toasts and tooltips get vanilla buttons, sliders, text fields, tabs, tooltip and pop-up frames, a blurred background and the Minecraft click sound. Settings become tiles **"Name: value"** in two columns, like Minecraft's Options — right-click a tile to reset it.
  - **Vanilla+** is the same, but settings are **rows like Litematica's**: name on the left, a value tile and **Reset** on the right.
  - Litematica's own screens stay vanilla in both. **Zone Focus** and **Easy Place Fix+** follow Material HUD's look unless they pick their own.
- **Count up to Y.** Settings → HUD → *Count up to a height*: the materials list counts everything **at or below Y** (−60…321) while the world still shows only your current layer — plan the whole lower part, build one layer at a time. The same toggle sits in the **Materials** tab of the schematics window.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib · Litematica · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.18 — 2026-10-21

Minecraft 26.3, 26.2, 1.21.11

#### New
- **Disable anything in the materials list.** Right-click a row → **Disable block** now also works for **water and lava** (waterlogged stairs, slabs and fences stay dry), for **entities** — item frames, armor stands, paintings, minecarts, boats, End crystals — and for **items in frames** (the frame stays, empty). A disabled row stays in place, greyed out, with a gold button to bring it back. It leaves the holograms, the HUD, Easy Place and every export, and is remembered for that schematic file.
- **Disable from the HUD list.** The same menu item in the **HUD list** turns the block off in every enabled placement's schematic.
- The export window now says how many disabled blocks are left out of the table.

#### Fixed
- fix — **"Only missing" did nothing in a schematic's list.** A placed schematic is now checked against the world over the whole build — Litematica layers and the hologram don't matter, the placement can even be turned off. With the toggle on, **Total** becomes **Left** (what is still to place, the same number as in the HUD list) and the rows are sorted by it. For a schematic that isn't placed, the toggle is greyed out and its tooltip says why.
- fix — **an entity hologram could vanish** (a frame or an armor stand) when the same schematic was placed twice: Litematica's rebuild threads could overwrite each other.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib · Litematica · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.17 — 2026-10-20

Minecraft 26.3, 26.2, 1.21.11

- fix — **the game crashed near an End Portal or End Gateway in a schematic** ("Missing elements in vertex") when Litematica's translucent schematic blocks were on. Their holograms are now drawn as Litematica draws them; entity holograms stay translucent.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.16 — 2026-10-20

Minecraft 26.3, 26.2, 1.21.11

Everything from 1.0.15 (until now only for 26.3) plus the new layer cut.

#### Selection tab — 3D preview by Voxy

- **Have Voxy installed? The 3D preview now uses it.** If the [Voxy](https://modrinth.com/mod/voxy) mod is in your modpack, the 3D view on the Selection tab is drawn by Voxy itself — the same far-terrain LODs you see in game: textured, far beyond the loaded chunks, smooth to rotate, and Material HUD builds nothing on its own. Clicking a block in the view sets a corner, as before.
- **Voxy is optional and not included.** Without it — or with an Iris shader pack on — Material HUD draws the preview itself, as before.
- **Without Voxy the preview is much lighter too.** Reading the loaded area no longer stalls the game: at 32 chunks it went from ~25 s to 1.5–5 s. Rotating no longer re-sends the whole model every frame — it stays in video memory. At 32 chunks the terrain is twice as detailed and colored by the top block (grass, not the stone under it). The 2D map is only built while 2D is shown.

#### Selection tab — Look at the world
- **"Look at the world"** (top row): the menu slides away and you play as usual — walk, fly, break and place blocks — while the coordinates card stays in the top-right corner as a HUD.
- **R** — cursor on the card: type coordinates, press +/−, move a corner to where you stand, or **Reset** the selection right there. **R** again or a click outside the card — back to the game. **Esc** — back to the whole Selection screen. The key is in mod settings → Hotkeys → Screens.
- **Coordinates card** in the top right: X Y Z columns in axis colors, one big square +/− button per field — top half +, bottom half − (Shift ×10, Ctrl ×100, right-click — the opposite step).
- **Simple / Multi-box** moved to the screen's Settings → **Multi-box selection** (off by default).
- fix — **the origin point showed 60000000 when the selection was empty.** Now 0.

#### Selection tab — cut by layers
- **Cut the preview layer by layer inside your selection.** The "Cut" slider on the Selection tab now walks the layers of the selected box: move it left and the 3D view shows only the selection, block by block, up to that layer — the line under the view says "Layer 3 of 6 · Y 83". Far right — the whole area again. The mouse wheel over the slider steps one layer.
- **Works with Voxy too.** While you cut, Material HUD draws the view itself (Voxy can't cut it); back at the far right, Voxy draws again.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21 · Syncmatica — optional · Voxy — optional

## 1.0.15 — 2026-10-20

Minecraft 26.3

#### Selection tab — 3D preview by Voxy

- **Have Voxy installed? The 3D preview now uses it.** If the [Voxy](https://modrinth.com/mod/voxy) mod is in your modpack, the 3D view on the Selection tab is drawn by Voxy itself — the same far-terrain LODs you see in game: textured, far beyond the loaded chunks, smooth to rotate, and Material HUD builds nothing on its own. Clicking a block in the view sets a corner, as before.
- **Voxy is optional and not included.** Without it — or on the Vulkan renderer, or with an Iris shader pack on — Material HUD draws the preview itself, as before.
- **Without Voxy the preview is much lighter too.** Reading the loaded area no longer stalls the game: at 32 chunks it went from ~25 s to 1.5–5 s. Rotating no longer re-sends the whole model every frame — it stays in video memory. At 32 chunks the terrain is twice as detailed and colored by the top block (grass, not the stone under it). The 2D map is only built while 2D is shown.

#### Selection tab — Look at the world
- **"Look at the world"** (top row): the menu slides away and you play as usual — walk, fly, break and place blocks — while the coordinates card stays in the top-right corner as a HUD.
- **R** — cursor on the card: type coordinates, press +/−, move a corner to where you stand, or **Reset** the selection right there. **R** again or a click outside the card — back to the game. **Esc** — back to the whole Selection screen. The key is in mod settings → Hotkeys → Screens.
- **Coordinates card** in the top right: X Y Z columns in axis colors, one +/− button per field — top half +, bottom half − (Shift ×10, Ctrl ×100, right-click — the opposite step).
- **Simple / Multi-box** moved to the screen's Settings → **Multi-box selection** (off by default).
- fix — **the origin point showed 60000000 when the selection was empty.** Now 0.

For **26.2** and **1.21.11** this came out in **1.0.16**.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25 · Syncmatica — optional · Voxy — optional

## 1.0.14 — 2026-10-18

Minecraft 26.3, 26.2, 1.21.11

#### Highlight all chests
- **Each item gets its own color.** With "Highlight all chests" on, every chest is labeled with the needed items it holds, in the same color as the item's bar in the HUD — like the HUD page search, but over the whole schematic.
- **You can tell the mode apart:** in "all chests" mode each mark has a thin **gold frame**; the HUD-list search has none.
- **The "Highlight all chests" key switches modes:** all chests ↔ HUD list only, with no warning window (the warning stays on the option in settings). A short message says which mode is on. Turning it off goes straight back to the HUD list instead of clearing the marks. The key is unbound by default.

#### Materials
- **Disable on current layer.** "Disable on a layer…" in the material list now opens on the layer you are building: the single layer Litematica shows, or the layer of the last block you placed in this schematic. Right-click a row → **Disable on current layer (N)** does it in one click.

#### HUD
- fix — **the "chunks beyond render distance" line stretched the HUD to the left.** A line longer than the panel now scrolls instead.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21 · Syncmatica — optional

## 1.0.13 — 2026-10-17

Minecraft 26.3, 26.2, 1.21.11

Small fix on top of 1.0.12.

#### Fixed
- fix — **the label over highlighted chests read "All schematic materials"**, as if every chest held everything. It now says **Has schematic materials**: the chest holds at least one item the selected schematic needs.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21 · Syncmatica — optional

## 1.0.12 — 2026-10-17

Minecraft 26.3, 26.2, 1.21.11

#### New — Litematica in the Material HUD look
- **All Litematica screens are restyled** in Material HUD colors: menus, lists, dialogs, fields, tooltips. Buttons that other mods add to Litematica stay where those mods put them. Option **Litematica menu in Material HUD colors**.
- **Litematica buttons open our screens** (option **Own screens instead of Litematica's**): **Area Editor** opens the new **Selection** tab, **Schematic Placements** and **Loaded Schematics** open **Placements**, **Load Schematics** opens **Library**. Hold Shift to get Litematica's own screen.

#### New — Selection tab
- **Mark an area without flying around it.** Corner 1 and corner 2 with X / Y / Z fields in red, green and blue. The button next to each field: left click +1, right click −1, the wheel works too. Every corner also has **to player** and **move in world** (Litematica's keys and tool move that corner).
- **Live preview of the loaded world** around you: **3D** (isometric) or **2D** (top-down map with shading). Click a block in the preview or on the map — the corners are set one after another. Works without Bobby or Voxy, uses only the chunks the game has loaded.
- **Quality / Optimized** and a **Cut** slider to look inside.
- **Origin point** — the point the schematic is placed by. Off: the corner with the smallest X/Y/Z. On: set it yourself. Hover the title for a short explanation.
- **Simple / Multi-box** selections, components in their own panel, **All selections…**, **Rename**.
- **Reset** removes the selection from the world and sets the fields to zero.
- **Analyze area** — the blocks inside the selection as a list in **Materials**.
- **Save schematic** — a panel with the name and Litematica's options (exclude entities, save from the hologram world, only visible blocks, support blocks). After saving you land in the **Library**: the new schematic is on top, framed in yellow with a **NEW** badge that fades out in 30 seconds.

#### New — Syncmatica
With Syncmatica installed and a server that has it:
- **Library**: **Place and share** in the right panel and in the right-click menu — place it, press Done, and it goes to the server.
- **Placements**: **Share** / **Remove from server**, a **server** tag on shared rows, **Hide for me**. **Server (N)** lists the server's placements: load, unload, download, remove.
- Moving a shared placement in the editor asks the server first and sends the new position on **Done**.

#### New — highlight all chests
- **Highlight all chests regardless of layer/page** (Search tab, off by default): every chest, barrel and shulker box holding anything from the whole schematic is marked in yellow — not just the current layer or HUD page. Asks before turning on, because there can be a lot of marks. Hotkey **Highlight all chests**, unbound by default.

#### New — placement editor
- **Layers block**: mode (all, single, all below, all above, range), the layer axis, the layer number with −/+ (hold for faster), **To me**. A range has **From** and **To**.
- **Ctrl+C / Ctrl+V** in the fields. Paste three numbers "X Y Z" into a position field to set the whole position at once.
- The schematic and file names are shown in full — long names scroll inside the panel.
- X / Y / Z in red, green and blue.

#### New — Placements and Library
- **Placements**: one **Placement** button instead of two. **Select** turns on checkboxes to delete several placements at once.
- **Library**: **Quality | Optimized** right on the preview.
- **Library and Zone Focus**: a schematic split into zones has its place buttons in the zone color with a dashed outline, and a new placement is split into the same zones right away.

#### Changed
- **Only missing** in **Materials** is now a gathering list: it hides only what is already built in the world. Your inventory and chests are no longer subtracted — turn on **Count storage** for that.

#### Fixed
- fix — **Orbit (O) in the placement editor crashed the game** on 1.21.11. It works now, without distance fog.
- fix — **the Material HUD button in Litematica's main menu could sit under another mod's button** (for example Printer). It now always finds a free spot next to other mods' buttons.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21 · Syncmatica — optional

## 1.0.11 — 2026-10-15

Minecraft 26.3, 26.2, 1.21.11

#### Fixed
- fix — **auto transfer took too much.** If you closed the container while part of a stack was being moved, the whole stack on the cursor went into your inventory. The window now waits until the transfer is settled with the server, puts any extra back into the container and only then closes — you get exactly what the list needs, also with high ping.
- fix — **far-away patterned banners undercounted after a restart or a layer switch.** When the same pattern stood in one chunk in different positions (floor and wall, different rotations), "Missing" showed fewer than really needed. Same for decorated pots and player heads.
- fix — **Bobby: placed armor stands and item frames counted as missing.** While Bobby keeps the build's chunk from its cache, placed stands and frames are no longer listed as missing, and correct patterned banners, pots and heads are no longer "wrong block".
- fix — **shelves next to Inventory Profiles Next.** IPN's auto-refill put the item straight back into the emptied hand, so a powered shelf never got filled.
- fix — **shelves could take off your armor.** When the needed item was already in the hotbar, the shelf helper could click the wrong slot and move a helmet or chestplate.
- fix — **custom heads were named "'s Head".** Heads with an empty profile name are now shown as **Player Head**.
- fix — **the HUD panel could cover tooltips at very low frame rates.**

#### New
- **Chest search looks inside shulker boxes and bundles** in any container — a bundle inside a box too. Containers you opened before the update are picked up the next time you open them.
- **Disable on a layer…** in the row menu of **Materials**: choose the layer with a field or a slider (1 to the height of the schematic). The block disappears only on that layer — holograms, the HUD list and Easy Place skip it there. Bring it back with the gold button of the row or **Reset**.
- **Take shulker boxes** (transfer, off by default): what loose items don't cover is taken in shulker boxes that hold it, whole. If the loose stacks would not fit in your inventory, boxes go first.
- **Leave the container after taking** (on by default, works with auto transfer): when it took what was needed and nothing on the list is left in the container, the window closes. For 1.5 s after that, Esc does not open the pause menu.
- **Instant transfer** (on by default): everything needed is taken in one tick. If a server kicks you for fast inventory clicks, turn it off — then it's 4 clicks per tick.

#### Changed
- **The HUD counts in stacks on every row**, the top one too: "2065 (32x64+17)". Shulker boxes ("1.2 SB") are now an option — **Count in shulker boxes**.
- **Part of a stack is taken in a few clicks**: 30 of 64 is 4 clicks instead of 32.
- **Faster recount with supports**: a big carpet layer recounts in about 5 ms instead of 33 ms.
- **New mod icon** in Mod Menu.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21

## 1.0.10 — 2026-10-13

Minecraft 26.3, 26.2, 1.21.11

#### Fixed
- fix — **the material list depended on what was rendered around you.** The HUD list is read straight from the schematic once more, so it shows the same numbers whether the area is rendered, the placement's rendering is off, or the build is thousands of blocks away in chunks that are not loaded.
- fix — **support blocks from the next layer were missing from the list.** When you build layer by layer, the blocks this layer stands on or hangs from are counted again: a floor under a torch, the chain above a lantern, the vine above a vine, the block under sand or gravel. No setting is needed for this any more (**Support from the next layer** now only affects holograms and Easy Place).
- fix — **far-away patterned banners counted as plain ones.** Banners with patterns, decorated pots with sherds and player heads are told apart everywhere, not only near you.
- fix — **"Missing" did not follow the build.** It goes down the moment a block is placed, supports included, and progress in chunks you have walked away from is still counted.

#### New
- **Signs count their support.** Standing, wall and hanging signs bring the block they stand on or hang from into the list of their layer.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21

## 1.0.9 — 2026-10-13

Minecraft 26.3, 26.2, 1.21.11

#### Disabled blocks stay in place
A disabled block no longer drops to the bottom of **Materials**. Its row stays where it was, with the same numbers, dimmed and crossed out, with a gold "turn back on" button on the row — like resetting a key on the Keys tab. It still does not count towards totals or exports.

#### Reset and the "Changed" filter
**Restore schematic** is now just **Reset**: it still brings every replaced and disabled block back, as in the file. Next to it, the new **Changed** switch shows only the replaced and disabled blocks.

#### Chat stays quiet
Chat messages are now off by default. Key presses (guard toggles, holograms by inventory, storage search, layer check) are confirmed by a short line above the hotbar instead. Your old setting is reset once to the new default; turn **Chat messages** back on in the settings if you want them.

#### Shorter names in the HUD
Banners and decorated pots are listed by their name only: the pattern and the sherds are already visible on the icon. The full description stays in the **Materials** table.

#### Fixes
- fix — with **Easy Place Fix+** installed next to Material HUD, the pick-block helpers of the two mods clashed (Mixin "Method overwrite conflict"). Each mod now keeps its own.

---

#### Also in this line: 1.0.8

The 1.21.11 version catches up with the main line: the last release for 1.21.11 was 0.0.18. Everything added since then is here, ported to Minecraft 1.21.11 and Litematica 0.26.

#### Everything since 0.0.18
- **The Schematics tab**: library with thumbnails and search, 3D preview, placing with the top view and orbit camera, the list of placements, materials, block replacement, export tables (Excel, Google Sheets, CSV, Discord, picture), trash.
- **Entities from the schematic** in the list and in Easy Place: armor stands, item frames, paintings and more.
- **Holograms**: only blocks you have, hide blocks without support, support from the next layer, translucent entity holograms.
- **Next to a chest**: the panel sits beside it, plus a "Move everything needed" button and automatic transfer.
- **The orbit camera** in the placement editor collides with blocks instead of flying through them.
- **Easy Place**: iron guards, finishing plants and powered shelves, giving up on cells the server refuses, and many fixes.

#### New: disable a block in the schematic
On the Schematics tab, in **Materials**, right-click a row and choose **Disable block**. The block leaves the holograms, the material list and Easy Place; the schematic file is not changed. The row stays in the list, crossed out, with a gold button that turns the block back on — like resetting a key on the Keys tab.

**Restore schematic** at the top of the list brings back every replaced and disabled block at once, as in the file. It shows up only while the schematic has changes.

#### New: chest highlight
Containers found by the search are now highlighted softly: a light fill in the shape of the block, a glow around it and a thin outline that slowly breathes. A double chest is one box. The color is the color of the item's row in the list, or one color of your choice (**Own highlight color** and **Hue**). **Highlight strength** sets how visible it is. A chest with several wanted items gets stripes of their colors.

#### New: corner stairs connect the right way
A corner stair can be saved in a schematic in two ways that look exactly the same. Schematics made with Axiom, WorldEdit or pasted without block updates often use the "other" one, and the stair connected as a straight one or turned the wrong way. Now Easy Place picks the facing that gives the right corner. Every stair position and connection was checked: 712 of 712 stairs come out right. A corner placed either way counts as done in Easy Place and in the material list.

#### New: toggle keys
Keys for **Block placing off the schematic** and **Do not dig the wrong thing**. No keys by default: set them on the Keys tab, in the new **Toggles** section. The "Holograms by inventory on/off" key moved there too. With Easy Place Fix+ installed, both mods share one key.

#### New: "Do not dig the wrong thing" in parts
Three switches under it, all on by default: **Outside the schematic**, **Correctly placed** and **Only in Easy Place mode** (off — the guard works whenever a schematic is shown). A block that differs from the schematic only in what it adjusts to its neighbours by itself — stair corners, fence and pane connections, leaves — now also counts as correctly placed and is protected.

#### Fixes
- fix — **Easy Place did not place some blocks until you took one in your hand or placed one yourself.** Litematica looked for the block in your inventory only with exactly the same item data, so a block with a name, a description or a server plugin tag was not found. Now it is found and taken into your hand like any other.

#### Only in the 1.21.11 version
- Text fields have no IME input preview: Minecraft 1.21.11 does not have it.
- Chests, shulker boxes, heads and banners in the 3D preview are turned only by facing.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21

## 1.0.8 — 2026-10-13

Minecraft 26.3, 26.2, 1.21.11

The 1.21.11 version catches up with the main line: the last release for 1.21.11 was 0.0.18. Everything added since then is here, ported to Minecraft 1.21.11 and Litematica 0.26.

#### Everything since 0.0.18
- **The Schematics tab**: library with thumbnails and search, 3D preview, placing with the top view and orbit camera, the list of placements, materials, block replacement, export tables (Excel, Google Sheets, CSV, Discord, picture), trash.
- **Entities from the schematic** in the list and in Easy Place: armor stands, item frames, paintings and more.
- **Holograms**: only blocks you have, hide blocks without support, support from the next layer, translucent entity holograms.
- **Next to a chest**: the panel sits beside it, plus a "Move everything needed" button and automatic transfer.
- **The orbit camera** in the placement editor collides with blocks instead of flying through them.
- **Easy Place**: iron guards, finishing plants and powered shelves, giving up on cells the server refuses, and many fixes.

#### New: disable a block in the schematic
On the Schematics tab, in **Materials**, right-click a row and choose **Disable block**. The block leaves the holograms, the material list and Easy Place; the schematic file is not changed. The row stays in the list, crossed out, with a gold button that turns the block back on — like resetting a key on the Keys tab.

**Restore schematic** at the top of the list brings back every replaced and disabled block at once, as in the file. It shows up only while the schematic has changes.

#### New: chest highlight
Containers found by the search are now highlighted softly: a light fill in the shape of the block, a glow around it and a thin outline that slowly breathes. A double chest is one box. The color is the color of the item's row in the list, or one color of your choice (**Own highlight color** and **Hue**). **Highlight strength** sets how visible it is. A chest with several wanted items gets stripes of their colors.

#### New: corner stairs connect the right way
A corner stair can be saved in a schematic in two ways that look exactly the same. Schematics made with Axiom, WorldEdit or pasted without block updates often use the "other" one, and the stair connected as a straight one or turned the wrong way. Now Easy Place picks the facing that gives the right corner. Every stair position and connection was checked: 712 of 712 stairs come out right. A corner placed either way counts as done in Easy Place and in the material list.

#### New: toggle keys
Keys for **Block placing off the schematic** and **Do not dig the wrong thing**. No keys by default: set them on the Keys tab, in the new **Toggles** section. The "Holograms by inventory on/off" key moved there too. With Easy Place Fix+ installed, both mods share one key.

#### New: "Do not dig the wrong thing" in parts
Three switches under it, all on by default: **Outside the schematic**, **Correctly placed** and **Only in Easy Place mode** (off — the guard works whenever a schematic is shown). A block that differs from the schematic only in what it adjusts to its neighbours by itself — stair corners, fence and pane connections, leaves — now also counts as correctly placed and is protected.

#### Fixes
- fix — **Easy Place did not place some blocks until you took one in your hand or placed one yourself.** Litematica looked for the block in your inventory only with exactly the same item data, so a block with a name, a description or a server plugin tag was not found. Now it is found and taken into your hand like any other.

#### Only in the 1.21.11 version
- Text fields have no IME input preview: Minecraft 1.21.11 does not have it.
- Chests, shulker boxes, heads and banners in the 3D preview are turned only by facing.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.20 · Litematica 0.26.16 · Java 21

## 1.0.7 — 2026-10-12

Minecraft 26.3, 26.2

#### New: the orbit camera no longer flies through blocks
In the placement editor's **Orbit** view the camera now collides with the world, like a player does. Rotating, zooming with the wheel, panning and **F** (whole schematic) all stop at the ground and at walls and slide along them instead of going through. Once the way is clear again, the camera catches up to where it was heading.

Only real blocks stop it: the schematic's holograms and liquids don't. The mouse aims from where the camera actually is, so clicks always match the picture.

Same as 1.0.7+26.3.

#### 📦 You need

Minecraft 26.2 · Fabric Loader · Fabric API · malilib 0.29.6 · Litematica 0.28.8 · Java 25

## 1.0.6 — 2026-10-10

Minecraft 26.3, 26.2

#### New: "Give up on cells the server refuses"
A toggle on the Easy Place tab, on by default. After six refusals from the server, Easy Place stops clicking that cell until the block in it changes, and says so in chat once.

An empty cell never changes, so a lectern you keep breaking and placing to reroll villager trades could get stuck. Turn the toggle off and refusals are not counted: Easy Place keeps clicking and writes nothing to chat. The refusal message now points at this toggle.

#### Fixes
- fix — **A cell Easy Place gave up on could not be filled by hand either.** The ban now applies only to Easy Place clicks.

Also out now for **Minecraft 26.2** as 1.0.6+26.2.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.5 — 2026-10-10

Minecraft 26.3

#### New: automatic transfer
A small "Auto" toggle sits next to "Move everything needed". While it glows gold, opening a chest, barrel, shulker box, dispenser or hopper takes exactly what the current page is short of, without a click. If there is nothing to take, it stays silent. Furnaces, crafting tables and trading are left alone. It works even while the material panel is hidden. The same option is in the settings: "Auto-transfer on open", off by default.

#### New: iron Easy Place guards
Two options on the Easy Place tab, both off by default. They work for every block you place, by hand too, and only inside the schematic:
- "Block placing off the schematic": nothing goes into an empty schematic cell;
- "Block placing the wrong block": a cell takes only the block the schematic wants.

Outside the schematic everything places as usual, and chests, doors and levers still open.

#### New: translucent entity holograms
Cushions, item frames, armor stands, paintings, minecarts and chests from the schematic are now drawn translucent, with the same ghost transparency as blocks, so they no longer look like real ones before you place them.

#### New: support frame
With "Hide blocks without support", the cell a hidden block needs to hold on to (the ceiling above a hanging sign, the floor under pointed dripstone) gets an amber frame, so you can see what to build first. While "Support from the next layer" is on, these support blocks outside the visible layer are counted in the material list, and the transfer button takes them too.

#### New: build progress on the 3D preview
In the schematic library, the preview of a placed schematic shows built blocks as they are, missing ones as a light blue ghost and wrong blocks in red, with "built N%" in the status line.

#### New: Zone Focus in the schematic library
With the next Zone Focus (0.0.8): split a schematic into zones right in the library, even before placing it (the grid is kept for the file and applied when you place it), see numbered zone frames and build progress on the preview, and get a live "Manage zones" card on the Zone Focus tab. Zone Focus then checks blocks through Ctrl+B.

#### Smaller changes
- Easy Place carves pumpkins with shears to match the schematic, face on the right side.
- Snow layers can be added from any side: look at the snow from the side or from below, and Easy Place clicks its top.
- Y shows and hides the material panel inside the inventory and containers too; text fields keep their letters.
- The "Schematic library" button in the settings header replaces the "Schematics ›" tab.
- Settings section headers got colour accents.

#### Fixes
- fix — **On servers, Easy Place did not place cushions on snow 5–8 layers deep** ("the server rejected it three times"). For cauldrons, hoppers and composters Easy Place now clicks a spot of the bottom you can see, or asks you to stand higher, instead of leaving the cushion on the rim. An armor stand on snow with no blocks around is placed too.
- fix — **Fast building on a server sometimes placed the wrong block.** Taking a block from the backpack into the hand now waits for the server to confirm the previous placement. Blocks in the hotbar keep full speed; singleplayer is unaffected.
- fix — **Candles, turtle eggs, sea pickles, petals, leaf litter and snow layers did not reach the required count.**
- fix — **A ring of curved rails was never placed.**
- fix — **"Fill blocks" never filled a cauldron.** Water now goes in bottle by bottle up to the level (a full one may take a bucket), lava and powder snow by bucket.
- fix — **In creative, "Only blocks from inventory" hid the whole schematic.** In creative the whole schematic is shown now.
- fix — **In creative, finishing that needs a tool did nothing** (books into a chiseled bookshelf, bone meal, flint and steel, shears, axe…).
- fix — **Block finishing did not work with "Only blocks from inventory".** A block you can finish with a tool you carry is no longer hidden.
- fix — **The schematic and highlights blinked for a split second** in "Only blocks from inventory" when the inventory changed.
- fix — **The transfer sometimes took more than needed.** Extra items now go back to the container.
- fix — **The material list undercounted pink petals, wildflowers and leaf litter.**

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.4 — 2026-10-09

Minecraft 26.3

#### New: "Move everything needed" button
Open a chest, barrel, shulker box or any other storage container and a button appears above the material list. One click takes from the container exactly what the current page is still short of: what is missing minus what you already carry. Whole stacks move with a shift-click, and from a bigger stack only the shortfall is taken, the rest stays in the container. The button never sits above the container window. The result is shown above the hotbar even with chat messages off. It can be turned off in the settings.

#### New: "Hide blocks without support"
Highlight tab, off by default. Holograms of blocks that have nothing to hold on to right now are not drawn: a hanging sign without a ceiling or a wall, glow berries and pale hanging moss with nothing above, a vine in the air, a torch without a floor. A chain counts as held when its first link is, so a whole vine shows up once its top hangs from something. The Ctrl+B check skips these cells too. Build the support and the hologram appears by itself.

#### New: "Support from the next layer"
Highlight tab, off by default. When a block of the visible layer hangs on a schematic block in the next layer (a hanging sign on one layer, the ceiling above it on the next), that support block is shown together with the layer and Easy Place can place it, although it is outside the layer.

#### Smaller changes
- The panel hides while a settings menu of any of our mods is open and comes back when you close it.
- "Only blocks from inventory" now turns on Ghost Alpha inner sides, and turns them back off when you leave the mode.

#### Fixes
- fix — **A visible hologram could not be placed through an invisible one** in the "Only blocks from inventory" mode: a hidden block in front still caught Easy Place's aim. Hidden holograms are now passed through.
- fix — **Holes in holograms next to hidden blocks.** A face next to a hidden block is now drawn.
- fix — **The panel covered item tooltips in the player inventory, the crafting table and furnaces.** The tooltip is now drawn over the panel there too.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.3 — 2026-10-08

Minecraft 26.3

#### New: Ctrl+B follows "Only blocks from inventory"
With "Only blocks from inventory" turned on (Highlight tab), the Ctrl+B check shows only cells whose block you have with you: missing blocks, wrong blocks and wrong states. Extra blocks are not shown in this mode, since nothing has to be placed there. The summary starts with "from inventory:". Pick up or drop items and the frames update by themselves, no need to restart the check. With the mode off, Ctrl+B works as before.

#### Fixes
- fix — **Flowers and pots showed without being in your inventory** in the "Only blocks from inventory" mode: tall flowers, door tops and potted plants were always drawn. A potted plant now needs a flower pot first, and tall flowers and doors need their own item.
- fix — **A patterned banner with a name or description was not taken from the inventory.** Easy Place only picked a banner that matched the schematic exactly, so a banner with the right pattern but a custom name stayed in the backpack and nothing was placed. Now any banner with the right pattern is used. The same goes for decorated pots and heads.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.2 — 2026-10-08

Minecraft 26.3

- fix — **Freezes when opening and closing chests.** The mod saved its whole chest index to disk right in the game thread every time you opened or closed a chest, even with storage search turned off and even when nothing changed. The index grows with every chest you open, so the freeze grew too. Now the file is written in the background and only when something actually changed.
- fix — **Item tooltips in containers are on top of the panel again.** With a chest or inventory open, the material panel covered the tooltip of the item under the cursor. Now the panel is drawn under the tooltip and under the item you carry with the mouse.
- fix — **Plants over an empty flower pot.** The hologram of the plant the schematic wants in a pot was drawn as a separate block next to the pot: a whole cactus block, or a full-size flower shifted to the side. Now the plant is drawn inside the pot, at the size and place it will have once planted.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.1 — 2026-10-08

Minecraft 26.3

#### New: table style before export
Excel, Google Sheets and the team sheet now ask how the table should look before exporting. Two styles, each shown as a small preview of the sheet:
- **Light grey** (new default): grey surfaces with violet and gold accents.
- **Dark violet**: the dark theme of the picture card.

Click a style, then "Export" (or just press Enter). The choice is remembered, so next time two Enters are enough. The same setting is under "Table style" in the Schematics settings. For the team sheet, copy the script again so it picks up the style.

#### New: holograms only for blocks you have
A new toggle in the Highlight tab, "Only blocks from inventory": the schematic shows holograms only for blocks whose item is in your inventory right now. It works inside the current layer and the Zone Focus zone, and the Ctrl+B check still reports everything. You can also bind a key to it.

#### New: the panel goes to the right of the chest
While a chest or inventory is open, the material panel now sits to the right of the window by default. Prefer the left side? Turn off "Right of the window" in the HUD tab. If one side is too tight, the panel uses the other.

The mod's settings screen now opens full-screen by default for new installs.

#### Fixes
- fix — **Chest search marked the wrong chests.** Opening a chest could save its contents onto another chest that you had clicked a moment before (a locked chest, a click eaten by Easy Place, or a double click). Marks then showed blocks where there were none, and disappeared or came back as you reopened chests. Each chest now records only its own contents. Wrong records fix themselves the next time you open those chests.
- fix — **Flower pots.** An empty pot where the schematic wants a pot with a flower no longer counts as a wrong block: the flower stays in the material list, its hologram is shown above the pot, and the pot is no longer outlined red.
- fix — **Redstone dust dots.** Easy Place placed single redstone dust as a cross even when the schematic had a dot. A cross powers the blocks around it and a dot does not, so circuits could behave differently. The dot is now made right after placing.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 1.0.0 — 2026-10-07

Minecraft 26.3

#### New: the Schematics tab
Everything about your schematics in one place, right inside Material HUD. Open it from the "Schematics ›" tab in the settings, or with the "Material HUD" button in Litematica's main menu.

- **Library.** Browse your schematics folder, search, sort, see thumbnails and details (size, block count, author). Drag a schematic file into the window to add it.
- **3D preview.** Look at a schematic with real blocks before you place it: isometric, top or front view, or cut it by height. Huge schematics are drawn in a simpler way to keep the game fast. If you have enough memory, the new "Schematic display → Quality" setting draws real blocks for them too.

- **Placing.** "Place here" puts the schematic in front of you and shows it from above. Drag it with the mouse, rotate, mirror, drop it to the ground, snap it to the chunk grid, or nudge it with the arrows and the − / + buttons. You can also grab a corner of the hologram to lift or lower it. Made a mistake? Ctrl+Z.

- **Placements.** All schematics placed in your world in one list: select, move, hide or remove them.
- **Materials.** A table of everything you need: how many, how many shulker boxes and stacks, what is already in your inventory and chests, what is still missing. Counts match the HUD.

#### New: replace a block in a schematic
Don't like a block in the schematic? Click its name in Materials and pick another one, for example spruce planks instead of oak. Search by name, similar blocks come first. All such blocks change in the schematic itself, not only in the list: in the holograms too, and Easy Place will place the new block. Direction, slab half and similar properties are kept. Your schematic file is not changed, the replacement is remembered after a restart, and one click brings the original block back.

#### New: export tables
Send the material list wherever your team works:
- **Excel**: a ready "Collection sheet". Each player picks their name from a list and marks what they have collected, and a chart shows who did how much.
- **Google Sheets**, or straight into your team's shared sheet.
- **CSV**, **Discord** (split into messages), a **picture** and a **list for Litematica**.

Files are named after the schematic, nothing gets overwritten, and a button opens the exports folder.

#### New: trash and deleting schematics
Delete a schematic right in the Library (right-click, or the Delete key). It goes to the new Trash tab and stays there for 30 days. Restore it with one click, or delete it for good. If the schematic is placed in your world, the game warns you first.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.25 — 2026-10-05

Minecraft 26.3

#### Fixes
- **Observers, pistons, dispensers, droppers and barrels now face the way the schematic says.** Easy Place turned the player for them, but the server reads these blocks' facing from the head, which catches up with a new angle only on the next server tick — so they were placed facing wherever you were actually looking. The mod now gives the server that tick before clicking. Only cells you look at from the wrong side wait (a couple of ticks); the next blocks with the same angle go without delay. Works with *Pose one tick ahead* on or off.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.24 — 2026-10-03

Minecraft 26.3

#### New
- **Panel beside an open container.** With a chest, barrel, shulker box or your inventory open, the material list moves next to the container window — to its left, or to its right when the left side is tight. With little room it shrinks a bit, and it never covers the window, the recipe book or the effects column. Toggle *Beside an open container* on the HUD tab, on by default.
- **Wider settings and a full-screen option.** The settings panel grows to fit every tab name (up to two thirds of the window); *Full-screen settings* (HUD tab → Settings screen) stretches it over the whole window.
- **Esc unbinds a hotkey.** While capturing a key, Esc leaves it unset; a second click on the field brings the previous key back, the reset button gives the default.

#### Fixes
- The search icon in the settings header is a gold magnifier aligned with the text, and the hint disappears once you click the field.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.23 — 2026-10-01

Minecraft 26.3

#### New: entities from the schematic
- **Armor stands, cushions, item frames, paintings, end crystals, minecarts and boats** now appear in the material list and are counted correctly — the cushion color, the item inside a frame and its rotation are checked too.
- **Easy Place places them:** look at the entity's ghost and press your Easy Place key. Item frames also get their item and rotation.
- Paintings: the game picks the picture by itself, so it may differ from the schematic (the list then shows it as wrong). Boats are counted but not placed.

#### New: finishing plants
- Easy Place **trims vine tips with shears** so they stop growing, **adds glow berries with bone meal** and **grows shelf mushrooms**. Keep shears and bone meal in your inventory.

#### New: powered shelves
- **Powered shelves** (the solid panel look) are filled from the schematic too: the mod lays out your hotbar and swaps it into the shelves in one click.
- Unpowered shelves now get **exactly one item per slot** instead of your whole stack.

#### Fixes
- **Glow berries, weeping/twisting vines and kelp were counted as one item per column** — now every block counts.
- Vines count per face; kelp under water shows as missing instead of wrong.
- Easier to aim at thin vine ghosts — build a vine column just by looking at it.

Two new settings, both on by default: **Count entities** (HUD tab) and **Place entities** (Easy Place tab).

## 0.0.22 — 2026-09-30

Minecraft 26.3, 26.2

- **Ready for newer Litematica.** Litematica 0.29.1 (26.3) added three entity maps to `MaterialListUtils.getMaterialList` and removed the old overload, which crashed the 26.3 build. 26.2 (0.28.x) is not affected, but the call goes through the same `core/MaterialListCompat` bridge, so a backport to 0.28.x will not crash the HUD.

Everything else, including how the banner rows count your inventory, is the same as 0.0.21 (the slab completion is still out). The number 0.0.22 is reused: the earlier 0.0.22 was withdrawn on 22.09.

#### 📦 You need

Minecraft 26.2 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.21 — 2026-09-22

Minecraft 26.3, 26.2

Built from 0.0.20 (16.09). In: the list survives a dimension change (toggle "Keep the list across dimensions"), banner patterns and pot sherds are visible in the list (one row per pattern, exact-match inventory count), a filled shulker box can be placed with "Schematic only" on, and the mod says once per cell when a banner with a specific pattern is missing. Not in: completing the second half of a slab — pulled until verified separately. The earlier 0.0.21 and 0.0.22 were withdrawn; this 0.0.21 replaces them.

#### 📦 You need

Minecraft 26.3 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.20 — 2026-09-16

Minecraft 26.2

Bug Fixes

## 0.0.19 — 2026-09-13

Minecraft 26.2

- **Easy Place no longer drops a spare block one cell up.** The solver compares against the schematic only on properties a click can set and forgives the ones the surroundings decide: stair shape, `snowy` on grass, `distance` and `persistent` on leaves, fence connections. The "is there work here" test read any difference as work, so an already-placed stair whose neighbour was still missing looked unfinished: the mod lifted Litematica's two-second hold, the click landed on an occupied cell, and vanilla placement pushed it one cell past the face, usually upwards. 110 block types were affected: 64 stairs, 12 kinds of leaves, three soils and odds and ends. A cell whose only difference lies beyond what placement can set now counts as done, and a second guard cancels any automated click into a cell that already holds what the schematic asks for. Candles, sea pickles and snow layers still count as work.
- **The placement rate now covers state repairs too.** Finishing a door, trapdoor, candle, repeater or shelf ran before "blocks per second" and, in a tick where a placement had already gone out, added a second interaction and another pair of rotation packets, exactly what a server-side timer anticheat watches. Repairs and placements now share one budget: one action per interval.

#### 📦 You need

Minecraft 26.2 · Fabric Loader · Fabric API · malilib · Litematica · Java 25

## 0.0.18 — 2026-09-02

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

- **Built-in Zone Focus module removed** — it lives on as the standalone [Litematica Zone Focus](https://modrinth.com/mod/litematica-zone-focus) mod. Install it if you used zones: the two mods work together exactly as before (zone-scoped counting, verification and search across the zone, the settings tab), and your zones are kept.
- **Five toggles removed — their behaviour is now always on:** "Open doors, set levers", "Fill shelves", "Cycle by clicking" (was off by default), "Page inside containers", "Count with the panel hidden".

#### Fixed

- **Easy Place no longer places a spare fence on top of the one it just placed** (same for walls, panes, bars and redstone wire). A placed block that differs from the schematic only by its neighbour connections now counts as done, and a click displaced off an occupied cell into a cell the schematic leaves empty is cancelled.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.19 · Litematica 0.26.14 · Java 21

## 0.0.17 — 2026-08-31

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

- **Zone clipping works again when the standalone Litematica Zone Focus mod is installed.** Material HUD carries a built-in copy of Zone Focus which correctly goes dormant when the standalone mod is present — but it still claimed the render hooks, and while dormant it filtered nothing. Schematic blocks, block entities, entities, the schematic raytrace and verifier mismatch markers stopped being limited to the active zone. The dormant module now leaves those hooks to the standalone mod.
- **Easy Place: a skipped cell is no longer silent.** With "Skip what would go in wrong" enabled, the mod cancels the click when it cannot place the block in the orientation the schematic asks for — most often an orientable block such as a log. Litematica counts the cell as handled either way and holds it for two seconds, so from the outside it looked as if the mod had simply stopped placing there. It now says so in chat, once per cell, and the reason goes to "Easy Place debug".

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.19 · Litematica 0.26.14 · Java 21

## 0.0.16 — 2026-08-30

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

- **The "Mark the zone" button in the inventory no longer hides under the effects panel.** Since 26.x the game draws active effects to the right of the inventory window — exactly where the button used to sit.
- **Its spot is recalculated every frame** instead of once when the screen opens, so a new effect, an opened recipe book or a GUI scale change are picked up immediately.
- **Order of preference:** right of the window, level with its top → left when effects are active → right below the effects panel when the recipe book occupies the left → below the window or against the screen edge on cramped resolutions. In creative the spot above the window is skipped: the tab row lives there.
- **Buttons other mods add to that screen are taken into account** — it will not sit on top of them.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.19 · Litematica 0.26.14 · Java 21

## 0.0.15 — 2026-08-29

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

Everything the 26.2 branch gained since 0.0.6 is now here:

- **Built-in modules with their own settings tabs** — Ghost Alpha (a transparency slider for the schematic ghosts, plus lean inner faces) and Easy Place (placement fixes for stairs, slabs, rails, levers, double chests, repeaters and more).
- **Zone Focus module** — split a placed schematic into zones and build it piece by piece: materials counted per zone, everything outside the zone not rendered at all, mouse marking, the zone list with progress.
- **Shelf check**, the **movable HUD panel**, storage-search fixes and the whole run of Easy Place fixes, up to tick-based click pacing that stops server timer anticheats from seeing bursts.
- **The mod's button on Litematica's Render Layers screen** no longer overlaps the new "All" tab added in Litematica 0.26.14.

**Two features are unavailable on this branch:** the hanging-sign guard in Easy Place (Litematica 0.26.x has no placement-protocol API to hook into) and lean inner faces for fluids (that renderer does not exist there) — lean inner faces for blocks work as usual.

#### 📦 You need

Minecraft 1.21.11 · Fabric Loader · Fabric API · malilib 0.27.19 · Litematica 0.26.14 · Java 21

## 0.0.14 — 2026-08-20

Minecraft 26.2

**Zone Focus** splits a placed schematic into zones so you can build it piece by piece. It lives as Material HUD's third built-in module, next to Ghost Alpha and Easy Place, on a tab of its own.

- **Off by default.** One toggle on the new Zone Focus tab turns it on; all keybinds ship unbound.
- **Every zone action lives on the tab**: split into a grid (by zone count or by zone size), one zone per sub-region, a zone from your Litematica selection, the zone list with progress, clear. An action that can't run right now is greyed out with the reason.
- **Materials are counted for the active zone**, and Litematica layers keep working inside it — you scroll layers within the zone as usual (or count the whole zone at once, there's a toggle). Storage search can cover the whole zone too.
- **Everything outside the zone is simply not rendered**: no geometry is built for it at all, so a huge schematic stops weighing on FPS while you build its piece.
- **Mark a zone with the mouse**: hold LMB and drag a box right in the world; the scroll wheel moves a face. There's a slot-like button next to your inventory for it.
- **The split screen shows a top-down diagram**: it repeats the actual shape of the schematic and draws the cut lines and the first zone in the same colours you see in the world. Numbers can now be typed in — click a number, type, Enter.
- **No duplicated settings.** Zone blocks are checked by Material HUD's usual block check and chests are found by its usual storage search — both simply follow the active zone.
- **Plays nice with the standalone mod.** If the separate Litematica Zone Focus is installed, it keeps running the engine, and the tab here edits its settings — the values are the same on both screens.

#### In action

#### 📦 You need

Minecraft 26.2 · Fabric Loader 0.18.2+ · Fabric API · malilib · Litematica · Java 25

## 0.0.13 — 2026-08-16

Minecraft 26.2

New icon

## 0.0.12 — 2026-08-14

Minecraft 26.2

- **fixed** — Rails now land in the exact orientation the schematic shows — straight lines, turns and slopes, all four rail types.
- **fixed** — Rails no longer get skipped or misplaced while waiting for neighbours; no more permanent holes in the track.
- **fixed** — Double chests: the second half no longer stays a single chest. It's placed with a sneak click on the first chest — reliable on servers, all copper variants included.

#### ✨ New

- **new** — Levers are switched to match the schematic — right after placement or when you aim at one set wrong. Toggle: "Open doors, set levers".

## 0.0.11 — 2026-08-13

Minecraft 26.2

- **fixed** — Crash (`ArrayIndexOutOfBoundsException`) while counting materials on
  schematics whose declared region size is larger than the actual block container.
  The counter walked past the end of the schematic data instead of stopping at its
  edge; such cells are now skipped, the way Litematica itself handles them.

#### 📦 You need

Minecraft 26.2 · Fabric Loader 0.18.2+ · Fabric API · malilib · Litematica · Java 25

## 0.0.10 — 2026-08-12

Minecraft 26.2

Material HUD 0.0.10 - EasyPlace update

#### ✨ New

- **Finishing blocks with tools.** Copper is de-oxidized with an axe and waxed with a honeycomb, dirt becomes farmland with a hoe and a path with a shovel, a candle lands on its cake. Where survival has no item at all (candle cake, farmland, path), the base block goes down first — but only if the finishing tool is in your inventory.
- **Stripped logs.** No stripped log in your chests? The mod places a plain one and strips it with an axe from your inventory. If you have the stripped one, it places that.
- **Shelf filling.** Chiseled bookshelves and item shelves are filled to match the schematic: books and items go into their slots with clicks, the way a player does it. A wrong item on a shelf is swapped out in one click.
- **"Blocks per second"** — placement speed as one simple slider (default 10). Anticheat complaining? Set it to 5.
- **"Never turn the player"** and **"Treat the server as vanilla"** — for servers with an anticheat: they remove the camera snaps and the "impossible" clicks that get you kicked.
- **"Schematic only"** — Easy Place can no longer put a stray block inside the schematic (that random extra fence). If something you need stops placing, turn it off.
- **Copper golem poses.** A statue is clicked with an empty hand until it matches the schematic pose (sitting, running, star).
- **"Easy Place debug"** — tells you in chat why a block did not place. Handy when something is not working.
- Fewer settings: everything that should always work now just works — no toggles.

#### 🔧 Fixed

- **fixed** — Flowers, grass, saplings, lichen now place by aiming at their cell — no need to hit the thin stem.
- **fixed** — Glow berries, vines, kelp, hanging moss finally place. A vine can be grown by aiming anywhere along its ghost.
- **fixed** — Double chests finally build. The first half goes down as a single chest and vanilla merges the pair the moment the second half lands. All 12 chests: regular, trapped and the copper ones. Before, the pair never even started.
- **fixed** — Double slabs no longer stay single. The mod sends the second click itself, aimed at the slab's real surface — all 68 slab blocks.
- **fixed** — Signs no longer pop the edit screen: a freshly placed schematic sign gets its text straight from the schematic, no editor window on every signpost. Clicking an already-written sign still opens the editor, the vanilla way.
- **fixed** — Doors and trapdoors open/close to match the schematic right away instead of staying however they landed.
- **fixed** — Lily pads and frogspawn place outside the schematic again. With Easy Place on RMB they used to be blocked everywhere in the world.
- **fixed** — `U` and `Ctrl+U` work inside an open chest — no need to close it to toggle or clear the search.
- **fixed** — Storage search no longer switches itself off when a page is collected: turn the page and the chest marks are back.
- **fixed** — Search marks no longer linger from an unloaded schematic — remove the schematic and the chest marks go out on their own.
- **fixed** — Server confirmation is read from the packet number instead of a 300 ms timer: no more vine columns growing past the schematic or extra candles on a laggy connection — and no idle waiting on a fast one.
- **fixed** — A cell the server keeps refusing is dropped after six confirmed refusals, with one chat message saying where and why — instead of being clicked every two seconds forever.
- **fixed** — Lanterns no longer stand upright where the schematic wants them hanging: the cell waits until its ceiling is built.

## 0.0.9 — 2026-08-10

Minecraft 26.2

#### 🪴 Potted plants

A potted plant is two blocks in one cell — an empty pot, and a plant put into it
afterwards. Easy Place could build neither half.

- **The pot goes down first.** A potted plant has no item of its own, so Litematica
  asked for the *plant*: you got a dandelion in the ground instead of a pot. The empty
  pot is now placed first.
- **A pot that already stands gets filled.** An occupied cell used to kill the position
  outright; now the next click puts the plant in — from your hand, offhand, hotbar or
  inventory.
- Sent **without sneaking**, so the flower goes *into* the pot, not on top of it. Server
  confirmation is awaited between the two clicks. Every pot variant, vanilla and modded.
- New toggle in **Easy Place**: **Pot the plants** *(on)*. Material list unchanged.

#### 🖱️ Automated clicks

The mod's own clicks on blocks that already stand — flint and steel, bucket, bare hand,
plant into a pot — no longer go back through placement analysis. Their position, face,
item and angle are already worked out; re-deciding them could only cancel a click that
was correct.

## 0.0.8 — 2026-08-10

Minecraft 26.2

EasyPlace

🔧 Fixed

- Patterned banners are no longer placed at random. The pattern lives in the item, not in the block, so Easy Place could only tell banners apart by colour: out of three white banners with different patterns you got whichever came first. The mod now takes exactly the banner the schematic asks for.
- No banner with the right pattern in your inventory — the spot is skipped instead of being filled with the wrong one.
- A renamed banner with the same pattern still works.
- Same fix for decorated pots and player heads — their differences live in the item too.

Everything else is as in 0.0.7.

## 0.0.7 — 2026-08-10

Minecraft 26.2

#### 🌫️ Ghost Alpha

Built-in module with its own settings tab. **Ghost transparency** runs from 0% (the usual solid ghosts) to 100% (ghosts gone, schematic still working) — shared with Litematica's own setting and applied in the same frame.

<details>
<summary><b>Rest of the settings</b></summary>

- **Inner sides** — also draw the faces between neighbouring ghosts. Off, a see-through wall reads as one shell instead of a stack of boxes.
- **Lean inner sides** (on) — inner faces only where two different blocks meet. Same picture as Litematica's stock mode, but megabytes of video memory instead of gigabytes on a large schematic.
- **Sort by distance** — overlapping ghosts blend in the right order (costs a few frames).
- Got the standalone Ghost Alpha mod? The module stands down, that mod owns the settings, nothing is duplicated.

</details>

#### 🧱 Easy Place

Built-in module with its own settings tab, fixing the blocks Litematica's Easy Place has always got wrong. Master switch **Fix Easy Place** (on) — off, Litematica places exactly as before.

<details>
<summary><b>What it fixes</b></summary>

- **Match the schematic state** (on) — picks the face, hit spot, look angle and sneak state that reproduce exactly the state the schematic asks for: facing, axis, slab and stair half, door hinge, bell attachment, stair side, all 16 rotations of signs and skulls.
- Sneaking goes out as a real input packet, so blocks land flush against chests, furnaces and doors instead of opening them.
- **Do not overshoot the count** (on) — candles, sea pickles, turtle eggs, pink petals, leaf litter and now snow layers get exactly as many clicks as the schematic asks for, and the next click waits for the server to confirm.
- **Turn for the right facing** (on) — petals, leaf litter and grindstones take their facing from where you look, so the mod turns you for the click and turns back right after.
- Grindstones and other attachable blocks go up in mid-air, in the orientation the schematic asks for. Levers and buttons genuinely need support, so those wait until it exists.
- **Aim at thin blocks in the air** (on) — candles, chains and hanging signs are targeted by their whole block, so you can place them pointing at empty space instead of a two-pixel outline.
- **Light and put out** (on) — flint and steel or a fire charge lights candles, candle cakes and campfires, a shovel puts a campfire out, a bare hand snuffs a candle.
- **Waterlog** (on) — a water bucket fills the block, an empty bucket drains it.
- **Open doors and trapdoors** (on) — doors, trapdoors and fence gates the schematic wants open get one bare right click.
- **Skip what would go in wrong** (on) — if no click can give the state the schematic asks for, usually because the block it must attach to is not built yet, the position is skipped instead of placed wrong.
- **Do not fill shelves** (on) — automated clicks on a shelf go through as sneaking, so the block is placed and nothing is dropped into the shelf.
- **Fill blocks** (off) — glowstone into a respawn anchor, an eye of ender into an end portal frame, water into a cauldron. Off by default, it spends items.
- **Cycle by clicking** (off) — repeater delay, comparator mode, daylight detector and note block are stepped one click at a time until they match.
- Laggy server? Raise **Wait for confirmation, ms**.
- Running third-party EasyPlaceFix? Its master toggle is held off while our module is on and Material HUD does all the fixes, so grindstones stop landing sideways or one cell above. Switch our module off with its own toggle and EasyPlaceFix is re-enabled exactly as it was, with a chat notice both ways.

</details>

#### 🗄️ Shelves with items — new check

With the block check (Ctrl+B) on, **Shelves with items** (off) marks shelves holding items green and filled, so you see at a glance where resources are lying. The client knows a shelf's contents without opening it, so the search is instant and, unlike container search, does not need you to visit each one. Radius and marker cap are shared with the block check, and **Also outline empty shelves** (off) puts a thin outline on the empty ones.

#### 🔧 Fixed

- Counting no longer stops together with the hidden panel (Y) — the numbers are right the moment you show it. The old behaviour stays as **Count with the panel hidden** (on).
- The reset confirmation no longer wipes your settings when you click No.

<details>
<summary><b>Interface fixes</b></summary>

- Settings tabs never wrap to a second row: padding shrinks, then labels are ellipsised with the full label in the tooltip, then the strip scrolls. The changed-settings dot no longer pushes the next tab out, and the tile widens itself to fit the tab row.
- Section notes no longer spill out of the tile — the title carries a **?** and the note opens as a tooltip, in full. No more Format Error in labels and tooltips.
- Add-on buttons no longer vanish in a narrow window (a **...** list stays behind), Back no longer shrinks to zero width, and the button on Litematica's screens no longer covers its search bar — it is now an icon square with a hover label, in the tab row.
- Keybinds: the button shows the keys as you press them, Escape without typing restores the previous binding (also on tab switch and on closing the screen), and capture no longer breaks after the first key.
- Space in the search box no longer toggles the focused setting, and searching no longer overwrites the tab's remembered scroll position.

</details>

#### 📦 Requirements

Minecraft 26.2 · Fabric Loader 0.18.2+ · Fabric API · malilib · Litematica · Java 25

## 0.0.6 — 2026-08-09

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

**Ctrl + B tells you what is wrong with what you have already built.** The mod compares the schematic with the real world and draws a coloured box around every block that does not match — missing, wrong block, wrong state, extra. Press it again to clear.

#### Why is mine better than the Litematica verifier?

|  | Litematica's verifier | **Ctrl + B** |
|---|---|---|
| **Start** | placement GUI → Verifier → Start | one keybind, no screen |
| **Scope** | the whole placement | what the panel counts — your current layers, or the **Zone Focus** zone |
| **Updates** | re-run the scan | **live** — the moment you place or break a block |
| **Result** | a list in a GUI + overlay | outlines in the world + a colour-coded chat summary |

|  |  |
|---|---|
| ![Before](https://cdn.modrinth.com/data/cached_images/60d53fe016acaa4c0db68abd2fa45ce9b8d90e77.jpeg) | ![After](https://cdn.modrinth.com/data/cached_images/2a08a0f8891f9914c953d75a0b1ddf97659edf61.jpeg) |
| **Before** — spot the mistakes yourself | **After** — Ctrl + B marks every one |

Both can run at the same time without conflicts.

| Colour | Meaning |
|---|---|
| 🟥 Red | missing block |
| 🟧 Orange | wrong block |
| 🟨 Yellow | wrong state |
| 🟦 Blue | extra block |

- **New** — markers are **live**: they appear and vanish as you place or break, and an area returning into render distance re-checks itself.
- **New** — the check follows **what the panel counts** — your layer selection, or the zone when **Zone Focus** owns the counting area. No conflicts with Zone Focus's own verification.
- **New** — a **Verification** settings tab: master switch, the four categories, see-through, box fill, draw range, box limit.

#### 🐛 Fix — chests marked for items they do not contain

Contents are remembered **per block position**, and several paths wrote the wrong ones — or wrote them where no reopening could ever correct them:

- **Fix** — a screen that was **not** the chest you clicked (your inventory, crafting table, furnace, a mount's inventory, a chest minecart or boat) got recorded as that chest's contents;
- **Fix** — a click that opened nothing (sneak-placing against a chest, a blocked or protected chest) still armed the recording for 15 s → the window is now **3 s**, and such clicks no longer arm it at all;
- **Fix** — two containers opened in quick succession could **swap** their contents;
- **Fix** — after a double chest was taken apart and rebuilt, the snapshot stranded on the other half stayed visible **forever** — every later opening wrote to a different key;
- **Fix** — an item-frame label kept a chest glowing for an item that had run out;
- **Fix** — blocks that only *look* like containers (crafter, chiseled bookshelf, jukebox, decorated pot, campfire, lectern) could carry a mark **nothing** could clear.

> **A mark is now shown only if opening that very container would overwrite it** — anything wrong heals itself the next time you open the chest, and leftovers from older versions are cleaned up automatically.

⚠️ **Known limitation** — on servers where a plugin replaces a chest's screen with its own (shops, crates, protection menus), that screen is indistinguishable from a real chest for the client, so its items may still be recorded. Deleting `config/layerlist_storage.json` clears such records.

#### Changed — chat messages are colour-coded

- **Changed** — every line starts with a `»` mark in the mod's blue — messages no longer blend into each other or into server chat;
- **Changed** — the verification summary paints *missing* 🟥, *wrong block* 🟧, *wrong state* 🟨, *extra* 🟦 — a category at zero stays grey;
- **Changed** — the storage search lists block names in **exactly** the colours of their diamonds in the world and their stripes in the panel;
- **Changed** — on is green, off is grey, anything needing attention is amber.

## 0.0.5 — 2026-08-08

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

- Build progress is now remembered. A chunk you have visited keeps its counted progress when it leaves render distance — and survives leaving the world or server. The HUD no longer resets to "not built" on low render distance or after a rejoin. New HUD line: "N chunks beyond render distance — using saved progress"; the old "counted as not built" line now only covers chunks you never visited. Saved progress lives in config/layerlist/verify-cache/ (one file per world and dimension), is refreshed the moment a chunk loads again, and is tied to the exact placement and layer selection — a moved schematic never picks up stale numbers.
- Fixed: closing the game straight from a world no longer loses the last seconds of saved progress.
- Fixed: rebinding hotkeys takes effect immediately — previously a newly assigned key stayed dead until restart.
- New: mouse buttons can be bound — any button except the left one, recorded just like keyboard keys.
- Fixed: the "No schematic loaded" warning no longer lies — any enabled placement now counts, not only the one selected in Litematica.
- Fixed: typing in the mod switcher's dropdown filter works on the "Move HUD" screen.
- Fixed: no more empty "Default:" line in tooltips of button rows.

## 0.0.3 — 2026-08-04

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

**An important changes**

- The material list is counted from the schematic file, not from what is drawn around you. A schematic larger than your render distance used to give truncated numbers; now every block of the layer, range or zone is counted however far it reaches.
- The world is still compared where it is loaded, so "built / not built" is exact nearby. Blocks beyond the render distance cannot be checked and count as not built — the line under the list says how many chunks that is.

**Visuals**

- New: "Mark colors from blocks" (off) in the Highlight tab. On — a mark takes the color of the block itself, as before. Off — the mod's bright palette. Switching recolors the current search right away.

<details>
<summary>Simple Colors</summary>

</details>

<details>
<summary>Based on the item’s colour </summary>

</details>

## 0.0.2 — 2026-08-03

Minecraft 26.2, 26.1.2, 26.1.1, 26.1, 1.21.11

1.21.11 port

## 0.0.1 — 2026-07-28

Minecraft 26.2

Initial release for Minecraft 26.2 (Fabric).

Requires Litematica and MaLiLib (built against 0.28.4 and 0.29.3) plus Fabric API. Client-side only.▎

