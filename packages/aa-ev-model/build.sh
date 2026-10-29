#!/bin/sh
# Build dist/aa.ev.model.zip from the payloads in dist/ (see tools/build_all.sh).
#
# The package carries no Hyundai/Google binaries: only lpatch deltas that
# rebuild our payloads on the head unit from its own Leafia v1.0.5 files, plus
# lpatch itself (GPL-3.0, from HMG Leafia Plus; third_party/lpatch).
#   files/<car>/HKMC_GPM.apk.lpd                 Leafia HKMC_GPM.apk -> ours
#   files/<car>/libautoreceiver_infobank.so.lpd  Leafia library      -> ours
#   files/<car>/MD5SUMS                          md5 of the rebuilt payloads
# Each delta is checked here by rebuilding the payload with the real lpatch.
#
# Env: PY (python with bsdiff4), LPATCH (host command for the ARM lpatch),
# CARS (tools/cars.sh; default: every car with inputs).
set -e
cd "$(dirname "$0")/../.."
PY="${PY:-python3}"
LPATCH="${LPATCH:-qemu-arm-static third_party/lpatch/lpatch}"
. tools/cars.sh
CARS="${CARS:-$(cars_present)}"
F=packages/aa-ev-model/files

rm -rf "$F"
mkdir -p "$F"
built=""
for car in $CARS; do
    car_inputs "$car"
    for f in "dist/$car/HKMC_GPM.apk" dist/libautoreceiver_infobank.so \
             "$LEAFIA_DIR/HKMC_GPM.apk" "$LEAFIA_DIR/libautoreceiver_infobank.so" third_party/lpatch/lpatch; do
        [ -s "$f" ] || { echo "missing $f" >&2; exit 1; }
    done
    mkdir -p "$F/$car"
    for n in HKMC_GPM.apk libautoreceiver_infobank.so; do
        case "$n" in
            *.apk) out="dist/$car/$n" ;;
            *) out="dist/$n" ;;
        esac
        "$PY" -I third_party/lpatch/mkdelta.py "$LEAFIA_DIR/$n" "$out" "$F/$car/$n.lpd"
        rm -f "$F/$car/$n.check"
        $LPATCH delta "$F/$car/$n.lpd" "$LEAFIA_DIR/$n" "$F/$car/$n.check" >/dev/null
        cmp "$F/$car/$n.check" "$out" || { echo "delta for $car/$n does not rebuild it" >&2; exit 1; }
        rm -f "$F/$car/$n.check"
    done
    ( cd "dist/$car" && md5sum HKMC_GPM.apk ) > "$F/$car/MD5SUMS"
    ( cd dist && md5sum libautoreceiver_infobank.so ) >> "$F/$car/MD5SUMS"
    built="$built $car"
done
[ -n "$built" ] || { echo "no car built (see tools/cars.sh)" >&2; exit 1; }
cp -f third_party/lpatch/lpatch "$F/lpatch"
chmod 755 "$F/lpatch"

out=dist/aa.ev.model.zip
rm -f "$out"
( cd packages/aa-ev-model && zip -q -r -D "$OLDPWD/$out" NEMESIS.data bin files scripts svc )
echo "built $out for:$built"
md5sum "$out"
