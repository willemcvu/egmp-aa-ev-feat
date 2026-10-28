#!/bin/sh
# Full build of the aa.ev.model deliverables from the pristine base files:
#   1. patch the AA receiver library      -> dist/libautoreceiver_infobank.so
#   2. build the energy-model template    -> dist/ev_batch.bin + EvModelTemplate.java
#   3. compile the GPM sender classes     -> gpm-ev/build/smali
#   4. patch the GPM smali tree           -> build/gpm-smali
#   5. assemble classes.dex, swap into the Leafia APK
#                                       -> dist/HKMC_GPM.classes.dex, dist/HKMC_GPM.apk
#   6. build the NEMESIS package          -> dist/aa.ev.model.zip
#
# Requires: ~/tools/env.sh (Android SDK), smali/baksmali, capstone in
# PY (e.g. /tmp/opencode/ev-venv/bin/python), zip, protoc for checks.
set -e
cd "$(dirname "$0")/.."
PY="${PY:-/tmp/opencode/ev-venv/bin/python}"
. "$HOME/tools/env.sh"

echo "== 1. library patch =="
"$PY" tools/patch_library.py

echo "== 2. energy model template =="
"$PY" tools/build_ioniq5_model.py   # Ioniq 5 values; Ford path: build_model_template.py
protoc -I reference --decode=vem.VehicleEnergyModel vehicle_energy_model.proto \
    < dist/ev_model.bin > /dev/null   # parse check

echo "== 3. compile sender classes =="
sh gpm-ev/build.sh

echo "== 4. patch GPM smali =="
"$PY" tools/patch_gpm_smali.py

echo "== 5. assemble dex + APK =="
smali a -j 1 --api 18 -o dist/HKMC_GPM.classes.dex build/gpm-smali
cp -f private/car-current-leafia105/HKMC_GPM.apk dist/HKMC_GPM.apk
cp dist/HKMC_GPM.classes.dex dist/classes.dex
( cd dist && zip -q HKMC_GPM.apk classes.dex && rm classes.dex )

echo "== 6. NEMESIS package =="
sh packages/aa-ev-model/build.sh

echo
echo "built:"
md5sum dist/libautoreceiver_infobank.so dist/HKMC_GPM.apk dist/aa.ev.model.zip
echo
echo "verify the package scripts: bash tools/test_package.sh"
