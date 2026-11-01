#!/bin/sh
# Build dist/aa.ev.model.zip for the cars given (see tools/build_all.sh):
#   sh packages/aa-ev-model/build.sh <car>...
#
# The package carries no Hyundai/Google binaries: only lpatch deltas that
# rebuild our payloads on the head unit from its own Leafia v1.0.5 files, plus
# lpatch itself (GPL-3.0, from HMG Leafia Plus; third_party/lpatch). Per car:
#   files/<car>/HKMC_GPM.apk.lpd                 Leafia HKMC_GPM.apk -> ours
#   files/<car>/libautoreceiver_infobank.so.lpd  Leafia library      -> ours
#   files/<car>/BASE                             md5 of Leafia's APK, odex, library
#   files/<car>/MD5SUMS                          md5 of the rebuilt payloads
#   files/<car>/NAME                             the car, for logs and pop-ups
# The installer picks the car whose BASE the live files match. Each delta is
# checked here by rebuilding the payload with lpatch.
#
# Inputs: $CARS_DIR/<car>/base/{HKMC_GPM.apk,libautoreceiver_infobank.so} and
# $DIST/<car>/{HKMC_GPM.apk,libautoreceiver_infobank.so,BASE,NAME}.
# Env: PY (python with bsdiff4), LPATCH (host command for lpatch),
# CARS_DIR (build/cars), DIST (dist), OUT (dist/aa.ev.model.zip).
set -e
cd "$(dirname "$0")/../.."
PY="${PY:-python3}"
LPATCH="${LPATCH:-qemu-arm-static third_party/lpatch/lpatch}"
CARS_DIR="${CARS_DIR:-build/cars}"
DIST="${DIST:-dist}"
OUT="${OUT:-dist/aa.ev.model.zip}"
F=packages/aa-ev-model/files

[ $# -gt 0 ] || { echo "usage: build.sh <car>..." >&2; exit 2; }
[ -s third_party/lpatch/lpatch ] || { echo "missing third_party/lpatch/lpatch" >&2; exit 1; }

rm -rf "$F"
mkdir -p "$F"
for car in "$@"; do
    B=$CARS_DIR/$car/base
    D=$DIST/$car
    for f in "$D/HKMC_GPM.apk" "$D/libautoreceiver_infobank.so" "$D/BASE" "$D/NAME" \
             "$B/HKMC_GPM.apk" "$B/libautoreceiver_infobank.so"; do
        [ -s "$f" ] || { echo "missing $f" >&2; exit 1; }
    done
    mkdir -p "$F/$car"
    for n in HKMC_GPM.apk libautoreceiver_infobank.so; do
        "$PY" -I third_party/lpatch/mkdelta.py "$B/$n" "$D/$n" "$F/$car/$n.lpd"
        rm -f "$F/$car/$n.check"
        $LPATCH delta "$F/$car/$n.lpd" "$B/$n" "$F/$car/$n.check" >/dev/null
        cmp "$F/$car/$n.check" "$D/$n" || { echo "delta for $car $n does not rebuild it" >&2; exit 1; }
        rm -f "$F/$car/$n.check"
    done
    cp -f "$D/BASE" "$D/NAME" "$F/$car/"
    # -t: "md5  name" on every platform (the installer greps for two spaces)
    ( cd "$D" && md5sum -t HKMC_GPM.apk libautoreceiver_infobank.so ) > "$F/$car/MD5SUMS"
    echo "$car: $(cat "$D/NAME")"
done
cp -f third_party/lpatch/lpatch "$F/lpatch"
chmod 755 "$F/lpatch"

case "$OUT" in /*) ZOUT=$OUT ;; *) ZOUT=$(pwd)/$OUT ;; esac
mkdir -p "$(dirname "$ZOUT")"
rm -f "$ZOUT"
if command -v zip >/dev/null 2>&1; then
    ( cd packages/aa-ev-model && zip -q -r -D "$ZOUT" NEMESIS.data bin files scripts svc )
else
    # no zip (e.g. Git Bash on Windows): the same layout, no directory entries
    "$PY" -I -c '
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
names = []
for top in ("NEMESIS.data", "bin", "files", "scripts", "svc"):
    p = os.path.join(root, top)
    if os.path.isfile(p):
        names.append(top)
    for d, _, fs in os.walk(p):
        names += [os.path.relpath(os.path.join(d, f), root).replace(os.sep, "/") for f in fs]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for n in sorted(names):
        i = zipfile.ZipInfo(n, (2026, 1, 1, 0, 0, 0))
        exe = n.endswith(".sh") or n == "files/lpatch"
        i.external_attr = (0o100755 if exe else 0o100644) << 16
        i.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(i, open(os.path.join(root, n), "rb").read())
' packages/aa-ev-model "$ZOUT"
fi
echo "built $OUT"
md5sum "$OUT"
