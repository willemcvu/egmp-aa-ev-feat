#!/bin/sh
# Build the USB-drive release zip from dist/aa.ev.model.zip.
#   sh tools/build_release.sh v0.1.0
# Run after tools/build_all.sh and tools/test_package.sh, from a clean,
# committed tree (the README is stamped with the commit).
# Output: dist/release/egmp-aa-ev-feat-<version>-usb.zip and SHA256SUMS.
set -e
cd "$(dirname "$0")/.."
VERSION="${1:?usage: build_release.sh <version>}"

[ -s dist/aa.ev.model.zip ] || { echo "missing dist/aa.ev.model.zip - run tools/build_all.sh" >&2; exit 1; }
if [ -n "$(git status --porcelain)" ]; then
    echo "working tree not clean - commit first" >&2
    exit 1
fi
COMMIT=$(git rev-parse --short HEAD)

# the package must still carry only deltas, lpatch and the manifest
if unzip -l dist/aa.ev.model.zip | grep -E '\.(apk|so|odex|dex)$'; then
    echo "package contains binaries it must not ship" >&2
    exit 1
fi

OUT=dist/release
NAME=egmp-aa-ev-feat-$VERSION-usb
STAGE=$OUT/$NAME
rm -rf "$STAGE" "$OUT/$NAME.zip"
mkdir -p "$STAGE/packages"
cp release/NEMESIS.data "$STAGE/NEMESIS.data"
cp dist/aa.ev.model.zip "$STAGE/packages/aa.ev.model.zip"
sed "s/@VERSION@/$VERSION/; s/@COMMIT@/$COMMIT/" release/README.txt > "$STAGE/README.txt"

( cd "$STAGE" && zip -q -r -X "../$NAME.zip" NEMESIS.data packages README.txt )
( cd "$OUT" && sha256sum "$NAME.zip" > SHA256SUMS && cat SHA256SUMS )
unzip -l "$OUT/$NAME.zip"
