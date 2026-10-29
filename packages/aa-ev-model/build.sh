#!/bin/sh
# Build dist/aa.ev.model.zip from the payloads in dist/ (see tools/build_all.sh).
#
# The package carries no Hyundai/Google binaries: only lpatch deltas that
# rebuild our payloads on the head unit from its own Leafia v1.0.5 files, plus
# lpatch itself (GPL-3.0, from HMG Leafia Plus; third_party/lpatch).
#   files/HKMC_GPM.apk.lpd                 Leafia HKMC_GPM.apk -> ours
#   files/libautoreceiver_infobank.so.lpd  Leafia library      -> ours
#   files/MD5SUMS                          md5 of the rebuilt payloads
# Each delta is checked here by rebuilding the payload with the real lpatch.
#
# Env: PY (python with bsdiff4), LPATCH (host command for the ARM lpatch).
set -e
cd "$(dirname "$0")/../.."
PY="${PY:-python3}"
LPATCH="${LPATCH:-qemu-arm-static third_party/lpatch/lpatch}"
BASE=private/car-current-leafia105   # Leafia v1.0.5 files as pulled from the car
F=packages/aa-ev-model/files

for f in dist/HKMC_GPM.apk dist/libautoreceiver_infobank.so \
         "$BASE/HKMC_GPM.apk" "$BASE/libautoreceiver_infobank.so" third_party/lpatch/lpatch; do
    [ -s "$f" ] || { echo "missing $f" >&2; exit 1; }
done

rm -rf "$F"
mkdir -p "$F"
for n in HKMC_GPM.apk libautoreceiver_infobank.so; do
    "$PY" -I third_party/lpatch/mkdelta.py "$BASE/$n" "dist/$n" "$F/$n.lpd"
    rm -f "$F/$n.check"
    $LPATCH delta "$F/$n.lpd" "$BASE/$n" "$F/$n.check" >/dev/null
    cmp "$F/$n.check" "dist/$n" || { echo "delta for $n does not rebuild it" >&2; exit 1; }
    rm -f "$F/$n.check"
done
cp -f third_party/lpatch/lpatch "$F/lpatch"
chmod 755 "$F/lpatch"
( cd dist && md5sum HKMC_GPM.apk libautoreceiver_infobank.so ) > "$F/MD5SUMS"

out=dist/aa.ev.model.zip
rm -f "$out"
( cd packages/aa-ev-model && zip -q -r -D "$OLDPWD/$out" NEMESIS.data bin files scripts svc )
echo "built $out"
md5sum "$out"
