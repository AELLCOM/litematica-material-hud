# Litematica Material HUD & Chest Finder

Shows the Litematica material list for the current layer as a HUD and marks the chests, barrels and shulker boxes that hold the needed blocks. Includes the Ghost Alpha and Easy Place modules.

[Modrinth](https://modrinth.com/mod/material-hud) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/litematica-material-hud-chest-finder) · [Discord](https://discord.gg/jTU2yawThv)

This branch (`main`) is the source of **1.0.22+26.3** for **Minecraft 26.3** (Fabric).
Branches: `main` (26.3), `mc/26.2` (26.2), `mc/1.21.11` (1.21.11). Release notes: [CHANGELOG.md](CHANGELOG.md).

## Building

You need JDK 25+ (Gradle comes with the wrapper).

```sh
./libs/fetch-libs.sh   # malilib, litematica, syncmatica, voxy, sodium from Modrinth, SHA-1 checked
./gradlew build        # the jar lands in build/libs/
```

The jars in `libs/` are compile-only: the mod talks to them at runtime, none of their code is bundled.
`aell-ui/` is the shared settings-screen module (MIT, by Aell_); the build copies it into the `com.aell.layerlist.shaded.ui` package so two mods carrying it do not clash.
`src/dev` is the test harness for the dev client; it is compiled but never packed into the jar.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Third-party credits and the disclaimer are in [NOTICE.md](NOTICE.md).

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

