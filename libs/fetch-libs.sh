#!/usr/bin/env bash
# Downloads the compile-only dependencies from Modrinth into libs/ and checks their SHA-1.
# They are not part of the mod and are not redistributed in this repository.
# A fourth column names a compile stub: the same jar without fabric.mod.json, so Loom does not treat it as a mod.
set -euo pipefail
cd "$(dirname "$0")"
while read -r name url sha1 stub; do
    [ -z "$name" ] && continue
    if [ -f "$name" ] && [ "$(sha1sum "$name" | cut -d' ' -f1)" = "$sha1" ]; then
        echo "ok      $name"
    else
        echo "fetch   $name"
        curl -fsSL -A "aell-mod-build" -o "$name.part" "$url"
        [ "$(sha1sum "$name.part" | cut -d' ' -f1)" = "$sha1" ] || { echo "SHA-1 mismatch: $name" >&2; rm -f "$name.part"; exit 1; }
        mv "$name.part" "$name"
    fi
    if [ -n "${stub:-}" ] && [ ! -f "$stub" ]; then
        echo "stub    $stub"
        python3 - "$name" "$stub" <<'PY'
import sys, zipfile
src, dst = sys.argv[1:3]
with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as b:
    for item in a.infolist():
        if item.filename != 'fabric.mod.json':
            b.writestr(item, a.read(item.filename))
PY
    fi
done < libs.txt
