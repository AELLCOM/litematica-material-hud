#!/usr/bin/env bash
# Downloads the compile-only dependencies (Litematica, malilib, Syncmatica, Sodium, Voxy) from Modrinth
# into libs/ and checks their SHA-1. They are not part of the mod and are not redistributed here.
set -euo pipefail
cd "$(dirname "$0")"
while read -r name url sha1; do
    [ -z "$name" ] && continue
    if [ -f "$name" ] && [ "$(sha1sum "$name" | cut -d' ' -f1)" = "$sha1" ]; then
        echo "ok      $name"; continue
    fi
    echo "fetch   $name"
    curl -fsSL -A "material-hud-build" -o "$name.part" "$url"
    [ "$(sha1sum "$name.part" | cut -d' ' -f1)" = "$sha1" ] || { echo "SHA-1 mismatch: $name" >&2; rm -f "$name.part"; exit 1; }
    mv "$name.part" "$name"
done < libs.txt
