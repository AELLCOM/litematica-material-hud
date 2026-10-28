# Litematica Material HUD & Chest Finder

A client-side Fabric addon for [Litematica](https://modrinth.com/mod/litematica): shows the material list of the
schematic layer you are building as a HUD, marks the chests, barrels and shulker boxes that hold the blocks you need,
and adds Easy Place fixes, a schematics library and more.

[Modrinth](https://modrinth.com/mod/material-hud) ·
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/litematica-material-hud-chest-finder) ·
[Discord](https://discord.gg/jTU2yawThv)

This branch (`mc/26.2`) is the source of **1.0.22+26.2** for **Minecraft 26.2**.
Other Minecraft versions live in their own branches: `main` (26.3), `mc/26.2`, `mc/1.21.11`.
Release history is in [CHANGELOG.md](CHANGELOG.md) (Russian).

## Building

You need JDK 25+ (Gradle comes with the wrapper).

```sh
./libs/fetch-libs.sh   # Litematica, malilib, Syncmatica, Sodium, Voxy from Modrinth, SHA-1 checked
./gradlew build        # the jar lands in build/libs/
```

The jars in `libs/` are compile-only: the mod calls their API at runtime but none of their code is bundled.
`aell-ui/` is the shared settings-screen module (MIT); the build copies it into the
`com.aell.layerlist.shaded.ui` package so two mods carrying it do not clash.
`src/dev` is the test harness for the invisible dev client (`LL_COVERAGE=<stand> ./gradlew runClient`); it is
compiled but never packed into the jar.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE). Bundled third-party code and fonts, and the disclaimer, are listed in
[NOTICE.md](NOTICE.md) (`aell-ui` — MIT, parts of VoxyMap LTS — MIT, see `LICENSE_VoxyMap`, fonts — SIL OFL 1.1).

This is an unofficial addon: it is not affiliated with or endorsed by the authors of Litematica and malilib,
nor by Mojang or Microsoft.

