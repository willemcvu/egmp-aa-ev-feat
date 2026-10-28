#!/bin/sh
# Build dist/aa.ev.model.zip from the payloads in dist/.
# Run from the repo root after tools/patch_library.py and the dex build:
#   tools/patch_library.py
#   tools/build_model_template.py
#   (cd gpm-ev && javac/d8/baksmali - see build.sh there)
#   tools/patch_gpm_smali.py && smali a ...
#   cp ... dist/HKMC_GPM.apk (classes.dex replaced)
#   sh packages/aa-ev-model/build.sh
set -e
cd "$(dirname "$0")/../.."

for f in dist/HKMC_GPM.apk dist/libautoreceiver_infobank.so; do
    [ -s "$f" ] || { echo "missing $f - build it first" >&2; exit 1; }
done

mkdir -p packages/aa-ev-model/files
cp -f dist/HKMC_GPM.apk packages/aa-ev-model/files/HKMC_GPM.apk
cp -f dist/libautoreceiver_infobank.so packages/aa-ev-model/files/libautoreceiver_infobank.so
( cd packages/aa-ev-model/files && md5sum HKMC_GPM.apk libautoreceiver_infobank.so > MD5SUMS )

out=dist/aa.ev.model.zip
rm -f "$out"
( cd packages/aa-ev-model && zip -q -r -D "$OLDPWD/$out" NEMESIS.data bin files scripts )
echo "built $out"
md5sum "$out"
